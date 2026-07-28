package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.UIUnregistration
import ai.rever.boss.ipc.proto.WidgetTree
import ai.rever.boss.ipc.proto.WidgetUpdate
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The plugin half of `PluginUIService` — a **client** of the kernel.
 *
 * `ui_protocol.proto` puts the plugin on the client side in both directions: the plugin dials the
 * kernel, streams `WidgetUpdate`s up, and reads `UIEvent`s back down the same call. This runtime
 * used to implement the service as a *server* and wait to be dialled, which stopped working the
 * moment the host was corrected to be the server too (BossConsole #50) — with both ends waiting,
 * no out-of-process plugin UI connects at all.
 *
 * Plugin-facing shape is unchanged from the old server implementation: [registerSurface] to claim a
 * surface, [sendWidgetUpdate] to push a tree, [uiEvents] to read interactions back.
 *
 * ### What the contract requires of this side
 * - `RegisterUI` before `StreamUI`, always.
 * - `StreamUI` carries no surface id: it is bound to the surface of its **first** `WidgetUpdate` and
 *   pinned there, so there is one call per surface and the first message must be a real tree.
 * - The call **ending** unregisters the surface at the kernel — reopening needs a fresh
 *   `RegisterUI`, so recovery here re-registers rather than just re-dialling.
 */
class PluginUiClient(
    kernelChannel: ManagedChannel,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val logger = LoggerFactory.getLogger(PluginUiClient::class.java)
    private val stub = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(kernelChannel)

    // Own job under the caller's context: [close] then ends the streams without touching anything
    // else the caller is running on that scope.
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _uiEvents = MutableSharedFlow<UIEvent>(extraBufferCapacity = EVENT_BUFFER)
    /** User interactions from the kernel, for every surface this plugin owns. Keyed by `surfaceId`. */
    val uiEvents: SharedFlow<UIEvent> = _uiEvents.asSharedFlow()

    private val surfaces = ConcurrentHashMap<String, Surface>()

    /** Terminal: [close] has run, and this client will not claim anything else. */
    @Volatile
    private var shutdown = false

    /**
     * One surface's half of the transport.
     *
     * [updates] is what plugin code pushes into. It replays the last tree so a call reopened after a
     * drop resends what the surface is currently showing — the plugin does not re-push on our
     * behalf — and so a tree pushed while the stream was still opening is not lost.
     */
    private class Surface(
        @Volatile var registration: UIRegistration,
    ) {
        val updates = MutableSharedFlow<WidgetUpdate>(replay = 1, extraBufferCapacity = EVENT_BUFFER)
        val lock = Mutex()
        @Volatile var pump: Job? = null
        @Volatile var closed = false

        /** Set once [MAX_ATTEMPTS] is exhausted: nothing is collecting [updates] any more. */
        @Volatile var gaveUp = false
    }

    /**
     * Claim a surface with the kernel and start streaming it.
     *
     * Non-suspending because plugin code registers panels from ordinary setup functions; the work is
     * launched on the client's scope. Failures are logged — a surface the kernel refused is a surface
     * that will not render, and there is nothing the caller can do about it inline.
     *
     * Re-registering a surface that is **already streaming** updates what [registrations] reports but
     * does not tell the kernel: delivering the change would mean tearing the stream down and
     * re-registering, which blanks the surface. Unregister first if a live surface has to change.
     */
    fun registerSurface(registration: UIRegistration) {
        if (shutdown) {
            logger.warn("Ignoring registration for {} — this client is closed", registration.surfaceId)
            return
        }
        val surface = surfaces.compute(registration.surfaceId) { _, existing ->
            existing?.also { it.registration = registration } ?: Surface(registration)
        }!!
        scope.launch { open(surface) }
    }

    /** Push a widget update. The kernel binds this surface's stream to the first one it sees. */
    suspend fun sendWidgetUpdate(update: WidgetUpdate) {
        val surface = surfaces[update.surfaceId]
        if (surface == null) {
            logger.warn(
                "Dropping a widget update for an unregistered surface: {} — call registerPanel/registerTabType first",
                update.surfaceId,
            )
            return
        }
        if (surface.gaveUp) {
            // A MutableSharedFlow with no collector never suspends — it overwrites the replay slot
            // and returns. Without this the plugin keeps rendering into a void, silently.
            logger.warn(
                "Widget update for {} goes nowhere: this surface was given up on after repeated failures",
                update.surfaceId,
            )
            return
        }
        surface.updates.emit(update)
    }

    /** Release a surface: tells the kernel, then ends our stream so nothing tries to reopen it. */
    suspend fun unregisterSurface(surfaceId: String) {
        val surface = surfaces.remove(surfaceId) ?: return
        surface.closed = true
        surface.pump?.cancel()
        try {
            stub.unregisterUI(UIUnregistration.newBuilder().setSurfaceId(surfaceId).build())
        } catch (e: StatusException) {
            // Best effort: the kernel drops the surface when the stream ends anyway.
            logger.debug("UnregisterUI for {} failed ({}) — the closing stream will clear it", surfaceId, e.status)
        }
        logger.info("Unregistered UI surface: {}", surfaceId)
    }

    /**
     * Stop streaming every surface. Called when the plugin process is shutting down.
     *
     * Ending the streams is also how the kernel learns these surfaces are gone — it has no other
     * notice — so this runs before the plugin's own scope is cancelled.
     */
    fun close() {
        shutdown = true
        surfaces.values.forEach { it.closed = true }
        surfaces.clear()
        job.cancel()
    }

    /** Registered surfaces, for diagnostics and for tests that assert what a plugin claimed. */
    fun registrations(): List<UIRegistration> = surfaces.values.map { it.registration }

    // ---- transport ----

    private suspend fun open(surface: Surface) {
        surface.lock.withLock {
            if (surface.closed || surface.pump?.isActive == true) return
            surface.pump = scope.launch { runStream(surface) }
        }
    }

    /** How one register-and-stream cycle ended, which is what decides whether to try again. */
    private enum class Cycle {
        /** The kernel ended the stream cleanly. It is done with this surface; do not re-register. */
        FINISHED,

        /** Something broke. Worth another attempt, but a bounded number of them. */
        FAILED,
    }

    /**
     * Keep this surface registered and streaming for as long as the kernel wants it.
     *
     * A stream that ends takes the kernel-side registration with it, so recovery is
     * register-then-reopen, not reconnect. Two things bound that:
     *
     * A **clean** end is the kernel saying it is finished with the surface — an `UnregisterUI` from
     * the host side, or the surface being closed — so it stops here. Re-registering would undo a
     * decision the host just made, and the surface would come back after the user closed it.
     *
     * A **failed** cycle counts against [MAX_ATTEMPTS] whether or not the stream ever opened. The
     * counter deliberately does not reset on a cycle that got as far as streaming: with the kernel
     * down, `registerUI` throws every second forever, per surface, for the life of the child JVM —
     * and the process most likely to be down is the one that spawned us.
     */
    private suspend fun runStream(surface: Surface) {
        var attempt = 0
        while (scope.isActive && !surface.closed && attempt < MAX_ATTEMPTS) {
            if (registerAndStream(surface) == Cycle.FINISHED) return
            if (surface.closed) return
            attempt++
            if (attempt >= MAX_ATTEMPTS) break
            delay(RETRY_DELAY_MS)
        }
        if (attempt >= MAX_ATTEMPTS) {
            surface.gaveUp = true
            logger.error(
                "Giving up on UI surface {} after {} failed attempts — it will not render, " +
                    "and further widget updates for it go nowhere",
                surface.registration.surfaceId,
                MAX_ATTEMPTS,
            )
        }
    }

    /** One register-and-stream cycle. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun registerAndStream(surface: Surface): Cycle {
        val surfaceId = surface.registration.surfaceId
        return try {
            val response = stub.registerUI(surface.registration)
            if (!response.success) {
                logger.error("Kernel refused UI surface {}: {}", surfaceId, response.errorMessage)
                return Cycle.FAILED
            }
            logger.info("Registered UI surface with the kernel: {}", surfaceId)

            stub.streamUI(outboundFor(surface)).collect { event -> _uiEvents.emit(event) }
            logger.info("UI stream for {} completed — the kernel is done with this surface", surfaceId)
            Cycle.FINISHED
        } catch (e: CancellationException) {
            // Our own shutdown, or the scope going away. Not a transport failure, and rethrowing is
            // what keeps cancellation cooperative.
            throw e
        } catch (e: StatusException) {
            reportStreamFailure(surfaceId, e)
            Cycle.FAILED
        } catch (e: Exception) {
            // A shut-down channel, a failure inside the outbound flow, anything else. Without this
            // the coroutine dies under the SupervisorJob: the surface stops streaming for good, with
            // no re-register and nothing in the log but a stack trace on stderr.
            logger.warn("UI stream for {} failed unexpectedly — will re-register", surfaceId, e)
            Cycle.FAILED
        }
    }

    /**
     * What this surface sends, starting with the update that binds the call.
     *
     * `StreamUI` has no surface id of its own and takes it from the first update, so one is seeded
     * rather than waiting for the plugin to push. The seed is the tree the surface is *currently*
     * showing where there is one — on a re-open the registration's tree is stale, and binding with
     * it would render a blank or old frame before the replay corrects it.
     *
     * Prepended rather than emitted into [Surface.updates]: that buffer replays one element, so
     * pushing the binding update through it would evict a tree the plugin sent while the stream was
     * still opening — losing exactly the first render.
     */
    private fun outboundFor(surface: Surface): Flow<WidgetUpdate> =
        flow {
            emit(surface.updates.replayCache.firstOrNull() ?: bindingUpdate(surface.registration))
            emitAll(surface.updates)
        }

    private fun reportStreamFailure(
        surfaceId: String,
        e: StatusException,
    ) {
        when (e.status.code) {
            // The kernel is telling us the surface is someone else's, and always will be.
            Status.Code.FAILED_PRECONDITION ->
                logger.error("UI surface {} is already streamed by another call: {}", surfaceId, e.status.description)

            Status.Code.CANCELLED ->
                logger.debug("UI stream for {} cancelled", surfaceId)

            else ->
                logger.warn("UI stream for {} failed ({}) — will re-register", surfaceId, e.status)
        }
    }

    /**
     * The update that opens a stream: the surface's registered tree, or an empty one.
     *
     * Never an update with neither arm of the `oneof` set — the kernel logs that as a malformed
     * message from a buggy sender, and it would be exactly that.
     */
    private fun bindingUpdate(registration: UIRegistration): WidgetUpdate =
        WidgetUpdate
            .newBuilder()
            .setSurfaceId(registration.surfaceId)
            .setFullTree(
                if (registration.hasInitialTree()) registration.initialTree else WidgetTree.getDefaultInstance(),
            ).build()

    private companion object {
        /** Matches the kernel's own per-surface event buffer, so neither side is the surprising one. */
        const val EVENT_BUFFER = 256
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 1_000L
    }
}
