package ai.rever.boss.plugin.runtime

import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("HostDeathWatchdog")

/** Exit code used when this process gives up because its host is gone. */
internal const val EXIT_ORPHANED = 3

/** pid of init/launchd, which POSIX uses as an orphan's new parent. */
private const val INIT_PID = 1L

/**
 * Bind this child JVM's lifetime to the BossConsole process that launched it.
 *
 * Resolution deliberately happens inside the guarded body. Environment access and the JDK process
 * APIs can throw, and losing an optional watchdog must not prevent an otherwise healthy plugin from
 * starting. A resolved-but-absent host is different: it means the runtime is already orphaned and
 * must exit.
 *
 * [hostResolver] and [onHostGone] are injectable for tests. Returns true when a watchdog was armed,
 * false when this runtime was halted as already orphaned or continued after a JDK refusal.
 */
internal fun installHostDeathWatchdog(
    hostResolver: () -> ProcessHandle? = { resolveHostHandle() },
    onHostGone: () -> Unit = ::haltAsOrphan,
): Boolean {
    val handle =
        try {
            hostResolver()
        } catch (e: Exception) {
            // Catch Exception rather than Throwable: VM failures must not be disguised as an
            // optional watchdog problem.
            logger.error("Could not resolve the host process - continuing unwatched", e)
            return false
        }

    if (handle == null) {
        logger.error("No live host process - refusing to run orphaned")
        onHostGone()
        return false
    }

    return try {
        handle.onExit().thenRun {
            logger.warn("Host process {} exited - shutting down", handle.pid())
            onHostGone()
        }
        logger.info("Watching host process {} - will exit when it does", handle.pid())
        true
    } catch (e: Exception) {
        // The host also reaps children from its shutdown hook. Continuing without this second line
        // of defence is safer than refusing to start a healthy plugin.
        logger.error("Could not watch host process {} - continuing unwatched", handle.pid(), e)
        false
    }
}

/**
 * Resolve the process named by `BOSS_HOST_PID`, or infer the OS parent for older hosts.
 *
 * A declared PID is authoritative: if it is absent, dead, or has been recycled, this runtime is
 * orphaned and must not fall back to an unrelated parent. PID reuse is detected without widening
 * the host protocol. The real host necessarily started no later than this child JVM; a process that
 * acquired the same PID after the host died necessarily started later. When either start instant is
 * unavailable we preserve the prior PID-only behaviour instead of rejecting a legitimate host.
 *
 * Blank `BOSS_HOST_PID` is treated as unset. A malformed value or this process's own PID warns and
 * falls back to the OS parent. An inferred pid-1 parent means POSIX already reparented this orphan to
 * init/launchd; an explicitly declared pid 1 remains valid for containerised hosts.
 */
internal fun resolveHostHandle(
    declared: String? = System.getenv("BOSS_HOST_PID"),
    osParent: () -> ProcessHandle? = { ProcessHandle.current().parent().orElse(null) },
    processLookup: (Long) -> ProcessHandle? = { ProcessHandle.of(it).orElse(null) },
    selfPid: Long = ProcessHandle.current().pid(),
    selfStartedAt: Instant? = ProcessHandle.current().info().startInstant().orElse(null),
): ProcessHandle? {
    val declaration = declared?.trim()?.takeIf(String::isNotEmpty)
    if (declaration != null) {
        val pid = declaration.toLongOrNull()
        when {
            pid == null ->
                logger.warn(
                    "BOSS_HOST_PID is set but is not a pid: '{}' - falling back to the OS parent",
                    declared,
                )

            pid == selfPid ->
                logger.warn(
                    "BOSS_HOST_PID={} is this process - a launcher exported the wrong pid; falling back to the OS parent",
                    pid,
                )

            else -> {
                val handle = processLookup(pid)
                if (handle == null || !handle.isAlive) {
                    logger.error("BOSS_HOST_PID={} names no live process - the host is already gone", pid)
                    return null
                }

                val hostStartedAt = handle.info().startInstant().orElse(null)
                if (
                    hostStartedAt != null &&
                    selfStartedAt != null &&
                    hostStartedAt.isAfter(selfStartedAt)
                ) {
                    logger.error(
                        "BOSS_HOST_PID={} was reused by a process started at {}, after this runtime started at {}",
                        pid,
                        hostStartedAt,
                        selfStartedAt,
                    )
                    return null
                }
                return handle
            }
        }
    }

    val parent = osParent()
    if (parent?.pid() == INIT_PID) {
        logger.error("Reparented to init (pid {}) - the host is gone", INIT_PID)
        return null
    }
    return parent
}

/**
 * Leave immediately, without running this runtime's or the loaded plugin's shutdown hooks.
 *
 * A graceful exit would have to unwind a still-accepting gRPC server and a channel that is
 * mid-retry, either of which can block forever after the kernel is gone. Plugin-owned persistence
 * must therefore happen before host loss, not in a shutdown hook. The final log line is redirected
 * to a file by the host and is emitted before this call.
 */
private fun haltAsOrphan() {
    Runtime.getRuntime().halt(EXIT_ORPHANED)
}
