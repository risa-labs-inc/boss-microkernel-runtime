package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.UIRegistrationResponse
import ai.rever.boss.ipc.proto.UIUnregistration
import ai.rever.boss.ipc.proto.WidgetUpdate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side gRPC implementation of PluginUIService running inside a plugin process.
 *
 * The kernel connects to this service to:
 * - Register UI surfaces via [registerUI]
 * - Exchange widget updates and UI events via [streamUI]
 *
 * Plugin code pushes widget updates via [sendWidgetUpdate].
 * User interaction events from the kernel are forwarded to plugin code via [uiEvents].
 */
class PluginUIServiceImpl : PluginUIServiceGrpcKt.PluginUIServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(PluginUIServiceImpl::class.java)

    // Events flowing from kernel → plugin code (user interactions)
    private val _uiEvents = MutableSharedFlow<UIEvent>(extraBufferCapacity = 256)
    val uiEvents: SharedFlow<UIEvent> = _uiEvents.asSharedFlow()

    // Widget updates flowing from plugin code → kernel renderer
    private val _widgetUpdates = MutableSharedFlow<WidgetUpdate>(extraBufferCapacity = 256)
    val widgetUpdates: SharedFlow<WidgetUpdate> = _widgetUpdates.asSharedFlow()

    private val registrations = ConcurrentHashMap<String, UIRegistration>()

    override suspend fun registerUI(request: UIRegistration): UIRegistrationResponse {
        registrations[request.surfaceId] = request
        logger.info(
            "Registered UI surface: id={}, type={}, name={}",
            request.surfaceId, request.surfaceType, request.displayName,
        )
        return UIRegistrationResponse.newBuilder().setSuccess(true).build()
    }

    /**
     * Bidirectional stream: receives widget updates from plugin code, emits UI events back.
     *
     * Plugin code sends WidgetUpdates (widget tree changes); the kernel renders them.
     * The kernel sends UIEvents (user interactions); plugin code handles them.
     */
    override fun streamUI(requests: Flow<WidgetUpdate>): Flow<UIEvent> = channelFlow {
        // Collect incoming widget updates and forward to internal shared flow
        val collectJob = launch {
            requests.collect { update ->
                _widgetUpdates.emit(update)
                logger.debug("Received widget update for surface: {}", update.surfaceId)
            }
        }
        // Stream outgoing UI events back to the kernel
        _uiEvents.collect { event ->
            send(event)
        }
        awaitClose { collectJob.cancel() }
    }

    override suspend fun unregisterUI(request: UIUnregistration): Empty {
        registrations.remove(request.surfaceId)
        logger.info("Unregistered UI surface: {}", request.surfaceId)
        return Empty.getDefaultInstance()
    }

    /** Called by plugin code to push a widget update to the kernel renderer. */
    suspend fun sendWidgetUpdate(update: WidgetUpdate) {
        _widgetUpdates.emit(update)
    }

    /**
     * Called by the kernel (via IPC) to deliver a user interaction event to plugin code.
     * Plugin code listens via [uiEvents].
     */
    suspend fun deliverEvent(event: UIEvent) {
        _uiEvents.emit(event)
    }

    /**
     * Register a UI surface locally (called by RemotePluginContext before the kernel connects).
     * The registration is stored and will be served when the kernel calls [registerUI].
     */
    fun registerSurface(registration: UIRegistration) {
        registrations[registration.surfaceId] = registration
        logger.info(
            "Pre-registered UI surface: id={}, type={}, name={}",
            registration.surfaceId, registration.surfaceType, registration.displayName,
        )
    }

    fun getRegistration(surfaceId: String): UIRegistration? = registrations[surfaceId]
    fun getAllRegistrations(): List<UIRegistration> = registrations.values.toList()
}
