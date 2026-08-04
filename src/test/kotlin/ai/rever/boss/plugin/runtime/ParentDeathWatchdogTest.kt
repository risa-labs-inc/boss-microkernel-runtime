package ai.rever.boss.plugin.runtime

import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    /**
     * Start a `sleep` that is **not** a child of this JVM, and return a handle to it.
     *
     * The shell backgrounds the sleep and exits, so the sleep is reparented away - exactly the
     * relationship a plugin child has to a process it did not spawn.
     */
    private fun detachedSleeper(): ProcessHandle {
        val launcher =
            ProcessBuilder("sh", "-c", "sleep 60 & echo \$!")
                .redirectErrorStream(true)
                .start()
        val pid =
            launcher.inputStream
                .bufferedReader()
                .readLine()
                .trim()
                .toLong()
        assertTrue(launcher.waitFor(10, TimeUnit.SECONDS), "launcher shell should exit promptly")

        val handle = ProcessHandle.of(pid).orElseThrow { AssertionError("sleeper $pid already gone") }
        assertFalse(
            ProcessHandle.current().children().anyMatch { it.pid() == pid },
            "the sleeper must not be a child of this JVM, or the test proves nothing",
        )
        return handle
    }

    @Test
    fun `no parent means exit immediately rather than run orphaned`() {
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
        try {
            val armed =
                installParentDeathWatchdog(
                    parent = Optional.of(host),
                    onParentGone = { goneCalls++ },
                )

            assertTrue(armed, "a live host should be watched")
            assertEquals(0, goneCalls, "the watchdog must not fire while the host is alive")
        } finally {
            host.destroyForcibly()
        }
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
}
