package ai.rever.boss.plugin.runtime

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class ParentDeathWatchdogTest {
    private val sleepers = mutableListOf<ProcessHandle>()

    @AfterTest
    fun killSleepers() {
        sleepers.forEach { runCatching { it.destroyForcibly() } }
        sleepers.clear()
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
            installParentDeathWatchdog(
                parent = Optional.empty(),
                onParentGone = { goneCalls++ },
            )

        assertFalse(armed, "there is no host to watch, so nothing should be armed")
        assertEquals(1, goneCalls, "an already-orphaned process must be shut down at startup")
    }

    @Test
    fun `a live host arms the watchdog without firing it`() {
        val host = detachedSleeper()
        var goneCalls = 0

        val armed =
            installParentDeathWatchdog(
                parent = Optional.of(host),
                onParentGone = { goneCalls++ },
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
            installParentDeathWatchdog(parent = Optional.of(host), onParentGone = { fired.countDown() }),
        )

        host.destroyForcibly()
        assertTrue(
            fired.await(30, TimeUnit.SECONDS),
            "onParentGone must run once the watched host exits (non-child onExit polls, so allow time)",
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
        installParentDeathWatchdog(parent = Optional.of(host), onParentGone = { fired.countDown() })

        assertTrue(fired.await(30, TimeUnit.SECONDS), "a dead host must fire immediately")
    }

    @Test
    fun `a JDK refusal leaves the plugin serving rather than killing it`() {
        // The documented fallback: if onExit() will not give us a completion future we lose the
        // watchdog but keep running, because the host still reaps its children on exit. Distinguish
        // it from the orphaned-at-startup branch, which also returns false - there, onParentGone
        // runs; here it must not.
        var goneCalls = 0

        val armed =
            installParentDeathWatchdog(
                parent = Optional.of(RefusingHandle),
                onParentGone = { goneCalls++ },
            )

        assertFalse(armed, "arming failed, so nothing is watching")
        assertEquals(0, goneCalls, "a refusal must not be treated as the host having exited")
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
