package ai.rever.boss.plugin.runtime

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The watchdog that stops this runtime outliving its host.
 *
 * Nothing else in the process notices a dead host: `awaitTermination` blocks on this process's
 * own gRPC server, and the kernel channel redials a dead peer indefinitely. Without the
 * watchdog every host launch leaves a full cohort of plugin JVMs running forever.
 *
 * These tests deliberately watch processes this JVM did **not** spawn. In production the watched
 * process is the parent, which is never a child of the watcher, and `ProcessHandle.onExit()`
 * takes a different path for non-children (it cannot reap them, so it polls). A test that
 * watched a spawned child would exercise the wrong half of the JDK and pass regardless.
 */
class HostDeathWatchdogTest {
    private val sleepers = mutableListOf<ProcessHandle>()

    @AfterTest
    fun killSleepers() {
        sleepers.forEach { runCatching { it.destroyForcibly() } }
        sleepers.clear()
    }

    /** The OS parent as a nullable handle, which is how the resolver reports one. */
    private fun osParent(): ProcessHandle? = ProcessHandle.current().parent().orElse(null)

    /**
     * Skip a test that needs a POSIX shell rather than failing it on Windows.
     *
     * [detachedSleeper] backgrounds a `sleep` through `sh -c`, which a Windows dev machine has
     * no answer for - the failure that produces says nothing about what was being tested. CI is
     * ubuntu-only, so this only ever fires locally.
     */
    private fun requirePosixShell() {
        org.junit.Assume.assumeFalse(
            "needs a POSIX shell to start a detached process",
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
        )
    }

    /**
     * Start a `sleep` that is **not** a child of this JVM, and return a handle to it.
     *
     * The shell backgrounds the sleep and exits, so the sleep is reparented away - exactly the
     * relationship a plugin child has to a process it did not spawn.
     *
     * stderr is deliberately *not* merged into stdout. If it were, a shell warning could land on the
     * line the pid is read from; a value that failed to parse would only be annoying, but one that
     * parsed to some other live pid would have the test SIGKILL an unrelated process on the machine.
     * The command check below is the second guard on that.
     */
    private fun detachedSleeper(): ProcessHandle {
        requirePosixShell()
        val launcher = ProcessBuilder("sh", "-c", "sleep 60 & echo \$!").start()
        val firstLine =
            launcher.inputStream
                .bufferedReader()
                .readLine()
        assertTrue(launcher.waitFor(10, TimeUnit.SECONDS), "launcher shell should exit promptly")

        val pid =
            requireNotNull(firstLine?.trim()?.toLongOrNull()) {
                "expected a pid on stdout, got: ${firstLine ?: "<no output>"}"
            }
        val handle = ProcessHandle.of(pid).orElseThrow { AssertionError("sleeper $pid already gone") }
        sleepers += handle

        // Only assert when the OS actually tells us. ProcessHandle.info() is best-effort for a
        // process this JVM does not own - macOS often leaves command() empty - and an unreadable
        // command is not evidence of a wrong pid. When it *is* readable, a mismatch means we are
        // about to SIGKILL something we did not start, so refuse.
        val command = handle.info().command().orElse("") + handle.info().commandLine().orElse("")
        if (command.isNotEmpty()) {
            assertTrue(
                command.contains("sleep"),
                "resolved pid $pid is $command, not the sleeper - refusing to kill an unrelated process",
            )
        }
        assertFalse(
            ProcessHandle.current().children().anyMatch { it.pid() == pid },
            "the sleeper must not be a child of this JVM, or the test proves nothing",
        )
        return handle
    }

    @Test
    fun `no host means exit immediately rather than run orphaned`() {
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                resolveHost = { null },
                onHostGone = { goneCalls++ },
            )

        assertFalse(armed, "there is no host to watch, so nothing should be armed")
        assertEquals(1, goneCalls, "an already-orphaned process must be shut down at startup")
    }

    @Test
    fun `a live host arms the watchdog without firing it`() {
        val host = detachedSleeper()
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                resolveHost = { host },
                onHostGone = { goneCalls++ },
            )
        assertTrue(armed, "a live host should be watched")

        // The JDK's non-child poller ticks on the order of 100ms, so asserting immediately after
        // arming would pass even for a watchdog that fires spuriously. Give it room to misbehave.
        Thread.sleep(600)
        assertEquals(0, goneCalls, "the watchdog must not fire while the host is alive")
        assertTrue(host.isAlive)
    }

    @Test
    fun `the watchdog fires when a host this JVM did not spawn exits`() {
        val host = detachedSleeper()
        val fired = CountDownLatch(1)

        assertTrue(
            installHostDeathWatchdog(resolveHost = { host }, onHostGone = { fired.countDown() }),
        )

        host.destroyForcibly()
        assertTrue(
            fired.await(30, TimeUnit.SECONDS),
            "onHostGone must run once the watched host exits (non-child onExit polls, so allow time)",
        )
    }

    @Test
    fun `a host that is already gone fires the watchdog at once`() {
        val host = detachedSleeper()
        host.destroyForcibly()
        // Block until the exit is observable, so this covers the race where the host dies
        // between resolving the handle and installing the watchdog.
        host.onExit().get(30, TimeUnit.SECONDS)

        val fired = CountDownLatch(1)
        installHostDeathWatchdog(resolveHost = { host }, onHostGone = { fired.countDown() })

        assertTrue(fired.await(30, TimeUnit.SECONDS), "a dead host must fire immediately")
    }

    @Test
    fun `a JDK refusal leaves the plugin serving rather than killing it`() {
        // The documented fallback: if onExit() will not give us a completion future we lose the
        // watchdog but keep running, because the host still reaps its children on exit. Distinguish
        // it from the orphaned-at-startup branch, which also returns false - there, onHostGone
        // runs; here it must not.
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                resolveHost = { RefusingHandle },
                onHostGone = { goneCalls++ },
            )

        assertFalse(armed, "arming failed, so nothing is watching")
        assertEquals(0, goneCalls, "a refusal must not be treated as the host having exited")
    }

    // ── resolveHostHandle: which process counts as the host ──────────────────────────────────
    //
    // This is where the environment is read, and it was the least-tested code in the change. The
    // pid-1 case below is the one that matters: POSIX reparents an orphan to init, so parent()
    // hands back a *live* handle for pid 1 rather than an empty Optional. Falling back to it would
    // arm the watchdog on launchd, which never exits - the process would outlive its host for the
    // life of the machine, which is the leak this whole change exists to close.

    private fun handleOf(pid: Long): ProcessHandle? = ProcessHandle.of(pid).orElse(null)

    @Test
    fun `an unset BOSS_HOST_PID falls back to the OS parent`() {
        val parent = osParent()

        val resolved = resolveHostHandle(declared = null, osParent = { parent }, selfPid = 4242)

        assertEquals(parent?.pid(), resolved?.pid())
    }

    @Test
    fun `a live BOSS_HOST_PID wins over the OS parent`() {
        val host = detachedSleeper()

        val resolved =
            resolveHostHandle(
                declared = host.pid().toString(),
                osParent = { null },
                selfPid = 4242,
            )

        assertEquals(host.pid(), resolved?.pid())
    }

    @Test
    fun `a BOSS_HOST_PID naming a dead process means orphaned, not fall back`() {
        val host = detachedSleeper()
        host.destroyForcibly()
        host.onExit().get(30, TimeUnit.SECONDS)

        // The OS parent is deliberately live here: falling back to it would hide a host that told
        // us who it was and then died.
        val resolved =
            resolveHostHandle(
                declared = host.pid().toString(),
                osParent = { osParent() },
                selfPid = 4242,
            )

        assertNull(resolved, "a named-but-dead host must resolve to orphaned")
    }

    @Test
    fun `a malformed BOSS_HOST_PID falls back to the OS parent`() {
        val parent = osParent()

        listOf("abc", "1234 # comment", "12.5").forEach { garbage ->
            val resolved = resolveHostHandle(declared = garbage, osParent = { parent }, selfPid = 4242)
            assertEquals(
                parent?.pid(),
                resolved?.pid(),
                "'$garbage' should warn and fall back, not resolve",
            )
        }
    }

    @Test
    fun `a blank BOSS_HOST_PID is unset, not malformed`() {
        // Both fall back to the OS parent, so the difference is only in what gets logged - and
        // that is the point: an exported-but-empty variable is a launcher no-op, and reporting it
        // as "set but is not a pid" buries the warning that exists for a genuine quoting slip
        // under one that fires for nothing. Asserted on the value the resolver reads, because an
        // assertion on the fallback cannot see the difference at all.
        listOf(null, "", "   ", "\t\n").forEach { blank ->
            assertEquals(DeclaredHostPid.Unset, declaredHostPid(blank), "'$blank' should read as unset")
        }
    }

    @Test
    fun `a BOSS_HOST_PID that is not a number is malformed, and says so`() {
        assertEquals(DeclaredHostPid.Malformed("abc"), declaredHostPid("abc"))
        assertEquals(DeclaredHostPid.Malformed("12.5"), declaredHostPid(" 12.5 "))
        assertEquals(DeclaredHostPid.Pid(1234), declaredHostPid(" 1234 "))
    }

    @Test
    fun `a blank BOSS_HOST_PID still falls back to the OS parent`() {
        val parent = osParent()

        val resolved = resolveHostHandle(declared = "   ", osParent = { parent }, selfPid = 4242)

        assertEquals(parent?.pid(), resolved?.pid())
    }

    @Test
    fun `a BOSS_HOST_PID naming this process falls back instead of watching itself`() {
        val parent = osParent()
        val self = ProcessHandle.current().pid()

        val resolved = resolveHostHandle(declared = self.toString(), osParent = { parent }, selfPid = self)

        assertEquals(
            parent?.pid(),
            resolved?.pid(),
            "watching ourselves would throw from onExit and degrade to running unwatched",
        )
    }

    @Test
    fun `an OS parent of pid 1 means orphaned, because POSIX reparents to init`() {
        // Verified on this platform: an orphan's ppid is 1 and ProcessHandle.of(1) is present and
        // alive, so parent() returning "empty" is not how being orphaned actually presents.
        assertNotNull(handleOf(1), "pid 1 should be resolvable, which is the whole problem")

        val resolved = resolveHostHandle(declared = null, osParent = { handleOf(1) }, selfPid = 4242)

        assertNull(resolved, "init is not a host worth watching - it never exits")
    }

    @Test
    fun `a containerised host that is pid 1 can still name itself`() {
        val resolved = resolveHostHandle(declared = "1", osParent = { null }, selfPid = 4242)

        assertEquals(1L, resolved?.pid(), "an explicit BOSS_HOST_PID=1 is the escape hatch")
    }

    @Test
    fun `no OS parent at all still means orphaned`() {
        val resolved = resolveHostHandle(declared = null, osParent = { null }, selfPid = 4242)

        assertNull(resolved)
    }

    @Test
    fun `a resolver that throws leaves the plugin serving rather than killing it`() {
        // Resolution reads the environment and calls into the JDK, and it used to run as a default
        // argument - outside this function's own try - so anything it threw escaped main() and the
        // plugin never started. Losing the watchdog costs the cases that outlive the host's
        // shutdown hook; refusing to start costs the user the plugin.
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                resolveHost = { throw IllegalStateException("environment read blew up") },
                onHostGone = { goneCalls++ },
            )

        assertFalse(armed, "nothing is watching")
        assertEquals(0, goneCalls, "failing to identify the host is not evidence that it died")
    }

    // ── BOSS_HOST_START_MS: the pid we were given is the process we were told about ──────────
    //
    // A pid is a reusable integer. Between the host exporting one and this runtime reading it, the
    // host can die and the number can be handed to something unrelated - and ProcessHandle.of is
    // happy to return a live handle for that stranger, which we would then watch instead. It may
    // well outlive us, which is the leak this whole mechanism exists to close, reached by a
    // different road.

    /** The sleeper's start time, or null where the OS will not say. */
    private fun startMillisOf(handle: ProcessHandle): Long? =
        handle.info().startInstant().orElse(null)?.toEpochMilli()

    @Test
    fun `a declared start time that matches accepts the pid`() {
        val host = detachedSleeper()
        val started = startMillisOf(host) ?: return // platform does not report it; see below

        val resolved =
            resolveHostHandle(
                declared = host.pid().toString(),
                declaredStartTime = started.toString(),
                osParent = { null },
                selfPid = 4242,
            )

        assertEquals(host.pid(), resolved?.pid())
    }

    @Test
    fun `a pid that has been recycled is treated as a gone host`() {
        val host = detachedSleeper()
        val started = startMillisOf(host) ?: return
        val anHourEarlier = started - 3_600_000

        // The OS parent is deliberately live: falling back to it would be the same mistake in a
        // different costume, and the host that declared this pid is gone either way.
        val resolved =
            resolveHostHandle(
                declared = host.pid().toString(),
                declaredStartTime = anHourEarlier.toString(),
                osParent = { osParent() },
                selfPid = 4242,
            )

        assertNull(resolved, "a live pid that started at another time is not the host we were told about")
    }

    @Test
    fun `a start time within the clock tolerance is still the host`() {
        val host = detachedSleeper()
        val started = startMillisOf(host) ?: return

        val resolved =
            resolveHostHandle(
                declared = host.pid().toString(),
                declaredStartTime = (started + 500).toString(),
                osParent = { null },
                selfPid = 4242,
            )

        assertEquals(host.pid(), resolved?.pid(), "half a second of rounding is not pid reuse")
    }

    @Test
    fun `an unusable start time accepts the pid rather than refusing to start`() {
        // Blank, malformed, or simply not exported: none of these is evidence of reuse, and a
        // runtime that refused the pid here would stop working on every host that does not send
        // one - which today is all of them.
        val host = detachedSleeper()

        listOf(null, "", "   ", "not-a-timestamp").forEach { declaredStartTime ->
            val resolved =
                resolveHostHandle(
                    declared = host.pid().toString(),
                    declaredStartTime = declaredStartTime,
                    osParent = { null },
                    selfPid = 4242,
                )
            assertEquals(host.pid(), resolved?.pid(), "'$declaredStartTime' should not refuse the pid")
        }
    }

    @Test
    fun `a start time the OS will not report accepts the pid rather than refusing it`() {
        // ProcessHandle.info() is best-effort, and for a process this JVM does not own some
        // platforms report nothing at all. An unreadable start time is not evidence of reuse, and
        // refusing there would stop the runtime working on those platforms entirely.
        assertTrue(
            isDeclaredHost(StartTimelessHandle, declaredStartTime = "1700000000000"),
            "an unreadable start time must not be read as a recycled pid",
        )
    }

    /** A live handle whose OS never reports a start time, as macOS does for a foreign process. */
    private object StartTimelessHandle : ProcessHandle by ProcessHandle.current() {
        override fun info(): ProcessHandle.Info = NoStartTime

        private object NoStartTime : ProcessHandle.Info {
            override fun command(): Optional<String> = Optional.empty()

            override fun commandLine(): Optional<String> = Optional.empty()

            override fun arguments(): Optional<Array<String>> = Optional.empty()

            override fun startInstant(): Optional<java.time.Instant> = Optional.empty()

            override fun totalCpuDuration(): Optional<java.time.Duration> = Optional.empty()

            override fun user(): Optional<String> = Optional.empty()
        }
    }

    /** A handle whose `onExit()` throws, as the JDK's does for the current process. */
    private object RefusingHandle : ProcessHandle {
        override fun onExit(): CompletableFuture<ProcessHandle> =
            throw IllegalStateException("onExit for current process not allowed")

        override fun pid(): Long = -1

        override fun info(): ProcessHandle.Info = ProcessHandle.current().info()

        override fun parent(): Optional<ProcessHandle> = Optional.empty()

        override fun children(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()

        override fun descendants(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()

        override fun isAlive(): Boolean = true

        override fun supportsNormalTermination(): Boolean = false

        override fun destroy(): Boolean = false

        override fun destroyForcibly(): Boolean = false

        override fun compareTo(other: ProcessHandle): Int = 0
    }
}
