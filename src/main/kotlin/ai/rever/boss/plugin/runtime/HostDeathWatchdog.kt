package ai.rever.boss.plugin.runtime

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val logger = LoggerFactory.getLogger("HostDeathWatchdog")

/** Exit code used when this process gives up because its host is gone. */
internal const val EXIT_ORPHANED = 3

/**
 * pid of init/launchd.
 *
 * POSIX reparents an orphan to init, so an orphaned process's parent is pid **1** - a live handle,
 * not an absent one. Seeing it where a host should be means the host is already gone.
 */
private const val INIT_PID = 1L

/**
 * How far the host's reported start time may differ from what it declared.
 *
 * Both sides read the same clock through the same JDK call, so an exact match is expected. The
 * tolerance is for the rounding a platform may apply between `startInstant()` on the host and the
 * same call in a different process - it is far short of the window that matters, because a pid is
 * not recycled onto a process that started within seconds of the one it replaced.
 */
private val START_TIME_TOLERANCE: Duration = Duration.ofSeconds(2)

/**
 * Exit as soon as the host process that spawned this one goes away.
 *
 * Nothing else in this process notices a dead host. [ChildProcessConnection.awaitTermination]
 * blocks on *this* process's own gRPC server, which the host's death does not touch, and the
 * kernel channel is a gRPC `ManagedChannel` - it answers a dead peer by redialling with backoff
 * for as long as the JVM lives. So an orphan sits in `awaitTermination` forever, holding ~100
 * threads and its heap, and reconnecting to nobody. One cohort accumulates per host launch;
 * 434 of them once held 27 GB and 45k threads on a developer machine.
 *
 * The host reaps its children on exit too (`KernelBootstrap`'s shutdown hook). This is the other
 * half: a hook cannot run when the host is SIGKILLed or dies in native code, and only the child
 * can cover that case.
 *
 * Which process *is* the host is a stated contract where possible and an inference otherwise: the
 * host may name itself in `BOSS_HOST_PID`, and only failing that do we assume our OS parent. The
 * distinction matters because the inference holds only while BossConsole spawns this JVM directly,
 * which it does today (`ProcessSpawner` calls `ProcessBuilder.start()` with no wrapper). Put a
 * launcher shell or a supervisor in between and the parent becomes a short-lived process, so we
 * would halt at startup with [EXIT_ORPHANED] - and the host would see an immediate child death with
 * no plugin UI, a symptom that looks nothing like its cause.
 *
 * **Known gap: a zombie host.** A host that has exited but has not been reaped by *its* parent
 * stays visible as a process, so `onExit()` does not fire and this runtime keeps serving a host
 * that is gone. Nothing here can tell that apart from a live host without reading platform process
 * state, and the situation ends as soon as anything reaps it. Named rather than defended against.
 *
 * [resolveHost] and [onHostGone] are injectable for tests. Returns true when a watchdog was armed,
 * false when there was no live host to watch, when resolving one failed, or when the JDK refused to
 * let us watch it.
 */
internal fun installHostDeathWatchdog(
    resolveHost: () -> ProcessHandle? = ::resolveHostHandle,
    onHostGone: () -> Unit = ::haltAsOrphan,
): Boolean {
    // Inside the guard, not a default argument. Resolution reads the environment and calls into
    // the JDK, so it is not obviously infallible - and as a default argument it ran *before* this
    // function body, so anything it threw escaped main() and the plugin failed to start. That is
    // the opposite of the rule the rest of this function follows: losing the watchdog costs us
    // only the cases that outlive the host's own shutdown hook, whereas refusing to start costs
    // the user the plugin.
    val host =
        try {
            resolveHost()
        } catch (e: Exception) {
            // Deliberately not onHostGone(): failing to work out who the host is says nothing
            // about whether it is alive, and halting on it would turn a resolution bug into a
            // plugin that never starts.
            logger.error("Could not work out which process is the host - continuing unwatched", e)
            return false
        }

    if (host == null) {
        logger.error("No live host process - refusing to run orphaned")
        onHostGone()
        return false
    }

    return try {
        host.onExit().thenRun {
            logger.warn("Host process {} exited - shutting down", host.pid())
            onHostGone()
        }
        logger.info("Watching host process {} - will exit when it does", host.pid())
        true
    } catch (e: Exception) {
        // Narrow on purpose: this branch means "the JDK would not give us a completion future".
        // Catching Throwable would report an OutOfMemoryError as a watchdog problem and carry on in
        // an unknown state.
        //
        // Keep serving rather than refuse to start: the host reaps its children on exit too, so
        // losing the watchdog costs us only the cases that outlive the host's shutdown hook.
        logger.error("Could not watch host process {} - continuing unwatched", host.pid(), e)
        false
    }
}

/**
 * What `BOSS_HOST_PID` said, as three outcomes rather than two.
 *
 * [Unset] and [Malformed] both fall back to the OS parent, so the difference between them lives
 * only in what gets logged - which is exactly why it is a type here. A variable exported empty is
 * a launcher no-op; a variable holding `'$HOST_PID'` is a contract stated wrongly, and the warning
 * that exists for the second is worthless if the first fires it too.
 */
internal sealed interface DeclaredHostPid {
    /** Absent, or present and blank. */
    data object Unset : DeclaredHostPid

    /** Present, and not a number. Worth a warning. */
    data class Malformed(val raw: String) : DeclaredHostPid

    data class Pid(val value: Long) : DeclaredHostPid
}

/** Read `BOSS_HOST_PID`. Blank counts as unset; see [DeclaredHostPid]. */
internal fun declaredHostPid(raw: String?): DeclaredHostPid {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return DeclaredHostPid.Unset
    val pid = trimmed.toLongOrNull() ?: return DeclaredHostPid.Malformed(trimmed)
    return DeclaredHostPid.Pid(pid)
}

/**
 * The host process to watch: whoever `BOSS_HOST_PID` names, else our OS parent.
 *
 * Preferring the explicit value makes the host relationship a contract rather than a guess, and it
 * survives an intermediate wrapper process. The fallback keeps this runtime working with hosts that
 * do not set it.
 *
 * Null means "no host worth watching, treat this process as orphaned". Three cases produce it, and
 * the first two used to end in a *live* handle that would never fire:
 *
 * - `BOSS_HOST_PID` names a process that is already gone. The host told us who it was and it died;
 *   guessing at our parent from there is how a dead host becomes a watched init process.
 * - Our OS parent is [INIT_PID]. POSIX reparents orphans to init, so `parent()` returns a live pid-1
 *   handle rather than nothing - watching it would never fire and this JVM would outlive its host
 *   for the life of the machine, which is the leak the watchdog exists to close. A host that
 *   genuinely is pid 1 (containerised) can still be watched by naming itself in `BOSS_HOST_PID`,
 *   which returns before this check.
 * - `BOSS_HOST_PID` names a live process that is **not** the host, which
 *   [declaredStartTime] catches where the host supplied one. See below.
 *
 * Parameters exist for tests; production uses the defaults.
 */
internal fun resolveHostHandle(
    declared: String? = System.getenv("BOSS_HOST_PID"),
    declaredStartTime: String? = System.getenv("BOSS_HOST_START_MS"),
    osParent: () -> ProcessHandle? = { ProcessHandle.current().parent().orElse(null) },
    selfPid: Long = ProcessHandle.current().pid(),
): ProcessHandle? {
    when (val named = declaredHostPid(declared)) {
        // A quoting slip in a launcher is the likeliest way to get here, and it is worth saying
        // out loud - which is why an exported-but-empty variable is Unset rather than this.
        is DeclaredHostPid.Malformed ->
            logger.warn(
                "BOSS_HOST_PID is set but is not a pid: '{}' - falling back to the OS parent",
                named.raw,
            )

        is DeclaredHostPid.Unset -> Unit

        is DeclaredHostPid.Pid -> {
            val pid = named.value
            // onExit() throws for the current process, so without this the mistake would surface
            // as "the JDK refused" and degrade silently to running unwatched.
            if (pid == selfPid) {
                logger.warn(
                    "BOSS_HOST_PID={} is this process - a launcher exported the wrong pid; falling back to the OS parent",
                    pid,
                )
            } else {
                val handle = ProcessHandle.of(pid).orElse(null)
                if (handle == null) {
                    logger.error("BOSS_HOST_PID={} names no live process - the host is already gone", pid)
                    return null
                }
                return handle.takeIf { isDeclaredHost(it, declaredStartTime) }
            }
        }
    }

    val parent = osParent()
    if (parent != null && parent.pid() == INIT_PID) {
        logger.error("Reparented to init (pid {}) - the host is gone", INIT_PID)
        return null
    }
    return parent
}

/**
 * Whether [handle] is the process the host named, rather than a pid that has been recycled.
 *
 * A pid is a reusable integer. Between the host exporting `BOSS_HOST_PID` and this runtime reading
 * it, the host can die and the number can be handed to something else - and `ProcessHandle.of` is
 * happy to return a live handle for that stranger. We would then watch a process that has nothing
 * to do with us and may well outlive us, which is the leak the watchdog exists to close, arrived at
 * by a different road. `ProcessHandle` compares start times internally for handles it already
 * holds; nothing checks the pid we were *given*.
 *
 * `BOSS_HOST_START_MS` closes that: the host exports its own `startInstant()` beside its pid, and a
 * pid whose process started at a different time is not the process we were told about. A host that
 * does not export it is trusted as before, so this is inert until the host cooperates - stated
 * rather than assumed, because a check nobody feeds is a check that does nothing.
 *
 * An unreadable start time is not evidence of reuse: `ProcessHandle.info()` is best-effort, and on
 * some platforms a process this JVM does not own reports nothing at all. Trusting the pid there
 * keeps the runtime working on those platforms, which is the same trade the command check in the
 * tests makes.
 */
internal fun isDeclaredHost(handle: ProcessHandle, declaredStartTime: String?): Boolean {
    val declaredMs = declaredStartTime?.takeIf { it.isNotBlank() }?.trim()?.toLongOrNull()
    if (declaredStartTime != null && declaredStartTime.isNotBlank() && declaredMs == null) {
        logger.warn(
            "BOSS_HOST_START_MS is set but is not a timestamp: '{}' - accepting the pid unverified",
            declaredStartTime,
        )
        return true
    }
    if (declaredMs == null) return true

    val actual = handle.info().startInstant().orElse(null)
    if (actual == null) {
        logger.debug(
            "The OS did not report a start time for pid {} - accepting BOSS_HOST_PID unverified",
            handle.pid(),
        )
        return true
    }

    val drift = Duration.between(Instant.ofEpochMilli(declaredMs), actual).abs()
    if (drift <= START_TIME_TOLERANCE) return true

    logger.error(
        "BOSS_HOST_PID={} started at {}, but the host declared {} - the pid was reused, so the host is gone",
        handle.pid(),
        actual,
        Instant.ofEpochMilli(declaredMs),
    )
    return false
}

/**
 * Leave immediately, without running shutdown hooks.
 *
 * A graceful exit would have to unwind a still-accepting gRPC server and a channel that is
 * mid-retry, either of which can block exit indefinitely - and there is nothing worth flushing,
 * because the kernel this process reports to is already gone. The log line above lands first:
 * stdout and stderr are redirected to files by the host, so they survive the host's own death.
 *
 * **This skips the plugin's shutdown hooks as well as ours.** A plugin that persists to its own
 * file or database on shutdown loses that write. `halt` is still the right call - a graceful exit
 * can hang forever, and a plugin JVM that will not die is the failure this whole mechanism exists
 * to prevent - but the cost is real and is not paid by this process alone.
 */
private fun haltAsOrphan() {
    Runtime.getRuntime().halt(EXIT_ORPHANED)
}
