package ai.rever.boss.plugin.runtime

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cross-platform process-lifetime coverage for the OOP runtime's host watchdog. */
class HostDeathWatchdogTest {
    private val sleepers = mutableListOf<ProcessHandle>()

    @AfterTest
    fun killSleepers() {
        sleepers.forEach { runCatching { it.destroyForcibly() } }
        sleepers.clear()
    }

    /**
     * Ask a short-lived Java launcher to create the sleeper, making it a non-child of this test JVM.
     * This exercises the same JDK `onExit` polling path as production without assuming `sh`,
     * `powershell`, or any other platform shell exists.
     */
    private fun detachedSleeper(): ProcessHandle {
        val fixtureClass = Class.forName(FIXTURE_CLASS)
        val fixtureClasses = Path.of(fixtureClass.protectionDomain.codeSource.location.toURI())
        val javaExecutable =
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    "java.exe"
                } else {
                    "java"
                },
            )
        val launcher =
            ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                fixtureClasses.toString(),
                FIXTURE_CLASS,
                "launch",
            ).redirectErrorStream(true).start()
        val firstLine = launcher.inputStream.bufferedReader().readLine()
        assertTrue(launcher.waitFor(10, TimeUnit.SECONDS), "fixture launcher should exit promptly")
        assertEquals(0, launcher.exitValue(), "fixture launcher output: $firstLine")

        val pid =
            requireNotNull(firstLine?.trim()?.toLongOrNull()) {
                "expected a sleeper pid on stdout, got: ${firstLine ?: "<no output>"}"
            }
        val handle = ProcessHandle.of(pid).orElseThrow { AssertionError("sleeper $pid already gone") }

        // Refuse to kill an unrelated process if the platform exposes enough metadata to identify it.
        val command = handle.info().commandLine().orElse("")
        if (command.isNotEmpty()) {
            assertTrue(
                command.contains(FIXTURE_CLASS),
                "resolved pid $pid is $command, not the fixture - refusing to kill it",
            )
        }
        sleepers += handle
        assertFalse(
            ProcessHandle.current().children().anyMatch { it.pid() == pid },
            "the sleeper must not be a child of this JVM, or the test proves nothing",
        )
        return handle
    }

    @Test
    fun `no host means exit immediately rather than run orphaned`() {
        var goneCalls = 0

        val armed = installHostDeathWatchdog(hostResolver = { null }, onHostGone = { goneCalls++ })

        assertFalse(armed)
        assertEquals(1, goneCalls)
    }

    @Test
    fun `a live host arms the watchdog without firing it`() {
        val host = detachedSleeper()
        var goneCalls = 0

        val armed = installHostDeathWatchdog(hostResolver = { host }, onHostGone = { goneCalls++ })

        assertTrue(armed)
        Thread.sleep(600)
        assertEquals(0, goneCalls)
        assertTrue(host.isAlive)
    }

    @Test
    fun `the watchdog fires when a host this JVM did not spawn exits`() {
        val host = detachedSleeper()
        val fired = CountDownLatch(1)
        assertTrue(installHostDeathWatchdog(hostResolver = { host }, onHostGone = { fired.countDown() }))

        host.destroyForcibly()

        assertTrue(fired.await(30, TimeUnit.SECONDS), "watching a non-child must observe its exit")
    }

    @Test
    fun `a host that is already gone fires the watchdog at once`() {
        val host = detachedSleeper()
        host.destroyForcibly()
        host.onExit().get(30, TimeUnit.SECONDS)
        val fired = CountDownLatch(1)

        installHostDeathWatchdog(hostResolver = { host }, onHostGone = { fired.countDown() })

        assertTrue(fired.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun `a JDK refusal leaves the plugin serving rather than killing it`() {
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                hostResolver = { RefusingHandle },
                onHostGone = { goneCalls++ },
            )

        assertFalse(armed)
        assertEquals(0, goneCalls)
    }

    @Test
    fun `a host resolution exception leaves the plugin serving`() {
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                hostResolver = { throw SecurityException("process table denied") },
                onHostGone = { goneCalls++ },
            )

        assertFalse(armed)
        assertEquals(0, goneCalls, "an unavailable watchdog is not proof that the host died")
    }

    @Test
    fun `a VM error during host resolution is not swallowed`() {
        assertFailsWith<AssertionError> {
            installHostDeathWatchdog(hostResolver = { throw AssertionError("broken VM") })
        }
    }

    @Test
    fun `an unset BOSS_HOST_PID falls back to the OS parent`() {
        val parent = StubHandle(pid = 72)

        val resolved = resolveHostHandle(declared = null, osParent = { parent }, selfPid = 4242)

        assertSame(parent, resolved)
    }

    @Test
    fun `a blank BOSS_HOST_PID is treated as unset`() {
        val parent = StubHandle(pid = 72)

        listOf("", " ", "\t\r\n").forEach { blank ->
            assertSame(parent, resolveHostHandle(declared = blank, osParent = { parent }, selfPid = 4242))
        }
    }

    @Test
    fun `a live BOSS_HOST_PID wins over the OS parent`() {
        val childStart = Instant.parse("2026-09-15T00:00:10Z")
        val host = StubHandle(pid = 81, startedAt = childStart.minusSeconds(10))

        val resolved =
            resolveHostHandle(
                declared = "81",
                osParent = { null },
                processLookup = { host },
                selfPid = 4242,
                selfStartedAt = childStart,
            )

        assertSame(host, resolved)
    }

    @Test
    fun `a dead declared host means orphaned rather than falling back`() {
        val parent = StubHandle(pid = 72)
        val deadHost = StubHandle(pid = 81, alive = false)

        val resolved =
            resolveHostHandle(
                declared = "81",
                osParent = { parent },
                processLookup = { deadHost },
                selfPid = 4242,
            )

        assertEquals(null, resolved)
    }

    @Test
    fun `a missing declared host means orphaned rather than falling back`() {
        val parent = StubHandle(pid = 72)

        val resolved =
            resolveHostHandle(
                declared = "81",
                osParent = { parent },
                processLookup = { null },
                selfPid = 4242,
            )

        assertEquals(null, resolved)
    }

    @Test
    fun `a malformed BOSS_HOST_PID falls back to the OS parent`() {
        val parent = StubHandle(pid = 72)

        listOf("abc", "1234 # comment", "12.5").forEach { garbage ->
            assertSame(
                parent,
                resolveHostHandle(declared = garbage, osParent = { parent }, selfPid = 4242),
            )
        }
    }

    @Test
    fun `a BOSS_HOST_PID naming this process falls back instead of watching itself`() {
        val parent = StubHandle(pid = 72)

        val resolved = resolveHostHandle(declared = "4242", osParent = { parent }, selfPid = 4242)

        assertSame(parent, resolved)
    }

    @Test
    fun `an inferred pid 1 means POSIX has reparented an orphan`() {
        val resolved = resolveHostHandle(declared = null, osParent = { StubHandle(pid = 1) }, selfPid = 4242)

        assertEquals(null, resolved)
    }

    @Test
    fun `a containerised host that is pid 1 can still name itself`() {
        val host = StubHandle(pid = 1)

        val resolved =
            resolveHostHandle(
                declared = "1",
                osParent = { null },
                processLookup = { host },
                selfPid = 4242,
                selfStartedAt = null,
            )

        assertSame(host, resolved)
    }

    @Test
    fun `no OS parent at all still means orphaned`() {
        assertEquals(null, resolveHostHandle(declared = null, osParent = { null }, selfPid = 4242))
    }

    @Test
    fun `a declared pid reused after this runtime started is rejected`() {
        val childStart = Instant.parse("2026-09-15T00:00:10Z")
        val recycled = StubHandle(pid = 81, startedAt = childStart.plusNanos(1))

        val resolved =
            resolveHostHandle(
                declared = "81",
                osParent = { null },
                processLookup = { recycled },
                selfPid = 4242,
                selfStartedAt = childStart,
            )

        assertEquals(null, resolved, "a process born after its alleged child cannot be the host")
    }

    @Test
    fun `equal process start instants do not create a false pid-reuse rejection`() {
        val start = Instant.parse("2026-09-15T00:00:10Z")
        val host = StubHandle(pid = 81, startedAt = start)

        val resolved =
            resolveHostHandle(
                declared = "81",
                osParent = { null },
                processLookup = { host },
                selfPid = 4242,
                selfStartedAt = start,
            )

        assertSame(host, resolved, "coarse platform timestamps may report equal start times")
    }

    @Test
    fun `missing process start metadata preserves pid-only compatibility`() {
        val host = StubHandle(pid = 81, startedAt = null)

        val resolved =
            resolveHostHandle(
                declared = "81",
                osParent = { null },
                processLookup = { host },
                selfPid = 4242,
                selfStartedAt = Instant.parse("2026-09-15T00:00:10Z"),
            )

        assertSame(host, resolved)
    }

    private class StubInfo(private val startedAt: Instant?) : ProcessHandle.Info {
        override fun command(): Optional<String> = Optional.empty()
        override fun commandLine(): Optional<String> = Optional.empty()
        override fun arguments(): Optional<Array<String>> = Optional.empty()
        override fun startInstant(): Optional<Instant> = Optional.ofNullable(startedAt)
        override fun totalCpuDuration(): Optional<Duration> = Optional.empty()
        override fun user(): Optional<String> = Optional.empty()
    }

    private open class StubHandle(
        private val pid: Long,
        private val alive: Boolean = true,
        startedAt: Instant? = null,
    ) : ProcessHandle {
        private val info = StubInfo(startedAt)

        override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture()
        override fun pid(): Long = pid
        override fun info(): ProcessHandle.Info = info
        override fun parent(): Optional<ProcessHandle> = Optional.empty()
        override fun children(): Stream<ProcessHandle> = Stream.empty()
        override fun descendants(): Stream<ProcessHandle> = Stream.empty()
        override fun isAlive(): Boolean = alive
        override fun supportsNormalTermination(): Boolean = true
        override fun destroy(): Boolean = false
        override fun destroyForcibly(): Boolean = false
        override fun compareTo(other: ProcessHandle): Int = pid.compareTo(other.pid())
    }

    private object RefusingHandle : StubHandle(pid = -1) {
        override fun onExit(): CompletableFuture<ProcessHandle> =
            throw IllegalStateException("onExit for current process not allowed")
    }

    private companion object {
        const val FIXTURE_CLASS = "ai.rever.boss.plugin.runtime.DetachedSleeperFixture"
    }
}
