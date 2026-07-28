package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.UIRegistrationResponse
import ai.rever.boss.ipc.proto.UIUnregistration
import ai.rever.boss.ipc.proto.WidgetTree
import ai.rever.boss.ipc.proto.WidgetUpdate
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the plugin dials the kernel, rather than waiting to be dialled.
 *
 * `ui_protocol.proto` makes the plugin the client in both directions. This runtime implemented the
 * service as a server instead, so once the host was corrected to be the server too (BossConsole
 * #50) neither end would ever call the other and no out-of-process plugin UI could connect. That
 * failure is invisible to a unit test of either side alone — it only shows up when something
 * stands up the kernel's half and waits to be called, which is what this does.
 *
 * The fake kernel here implements the same service the host does, so "the client opened a stream"
 * is asserted against the real generated stubs over a real (loopback) connection.
 */
class PluginUiClientTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var server: Server? = null
    private var channel: ManagedChannel? = null
    private var client: PluginUiClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        channel?.shutdownNow()
        server?.shutdownNow()
        server?.awaitTermination(5, TimeUnit.SECONDS)
        scope.cancel()
    }

    /** The kernel's half: records what the plugin sent, and can push events back down the stream. */
    private class FakeKernelUi(
        private val accept: Boolean = true,
    ) : PluginUIServiceGrpcKt.PluginUIServiceCoroutineImplBase() {
        val registered = ConcurrentLinkedQueue<UIRegistration>()
        val unregistered = ConcurrentLinkedQueue<String>()
        val updates = ConcurrentLinkedQueue<WidgetUpdate>()
        val streamsOpened = ConcurrentLinkedQueue<String>()

        /** Events to hand back to the plugin once it is streaming. */
        val outbound = MutableSharedFlow<UIEvent>(replay = 8, extraBufferCapacity = 8)

        override suspend fun registerUI(request: UIRegistration): UIRegistrationResponse {
            registered += request
            return UIRegistrationResponse
                .newBuilder()
                .setSuccess(accept)
                .setErrorMessage(if (accept) "" else "surface held by another process")
                .build()
        }

        override fun streamUI(requests: Flow<WidgetUpdate>): Flow<UIEvent> =
            channelFlow {
                streamsOpened += "opened"
                val pump =
                    launch {
                        requests.collect { updates += it }
                    }
                outbound.collect { send(it) }
                pump.cancel()
            }

        override suspend fun unregisterUI(request: UIUnregistration): Empty {
            unregistered += request.surfaceId
            return Empty.getDefaultInstance()
        }
    }

    private fun start(kernel: FakeKernelUi): PluginUiClient {
        val srv = ServerBuilder.forPort(0).addService(kernel).build().start()
        server = srv
        val ch = ManagedChannelBuilder.forAddress("127.0.0.1", srv.port).usePlaintext().build()
        channel = ch
        return PluginUiClient(kernelChannel = ch, parentScope = scope).also { client = it }
    }

    private fun panel(
        surfaceId: String = "console",
        tree: WidgetTree? = null,
    ): UIRegistration =
        UIRegistration
            .newBuilder()
            .setSurfaceId(surfaceId)
            .setSurfaceType("panel")
            .setDisplayName("Console")
            .setProcessId("plugin.console")
            .apply { tree?.let { setInitialTree(it) } }
            .build()

    /** Polls rather than sleeping a fixed time: the transport is asynchronous but fast. */
    private suspend fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        withTimeout(WAIT_MS) {
            while (!condition()) delay(POLL_MS)
        }
        assertTrue(condition(), what)
    }

    @Test
    fun `registering a surface calls the kernel`() =
        runBlocking {
            val kernel = FakeKernelUi()
            start(kernel).registerSurface(panel())

            awaitTrue("the kernel should have been asked to register the surface") {
                kernel.registered.isNotEmpty()
            }
            val request = kernel.registered.first()
            assertEquals("console", request.surfaceId)
            assertEquals("plugin.console", request.processId)
        }

    @Test
    fun `the plugin opens the stream itself`() =
        runBlocking {
            // The inversion, stated directly: nothing dials this plugin, so if it does not open the
            // call there is no UI transport at all.
            val kernel = FakeKernelUi()
            start(kernel).registerSurface(panel())

            awaitTrue("the plugin should have opened a StreamUI call") { kernel.streamsOpened.isNotEmpty() }
        }

    @Test
    fun `the stream is bound by a first update naming the surface`() =
        runBlocking {
            // StreamUI carries no surface id; the kernel binds the call to the first WidgetUpdate.
            // A call that opens and says nothing is reaped with DEADLINE_EXCEEDED.
            val kernel = FakeKernelUi()
            start(kernel).registerSurface(panel())

            awaitTrue("a binding update should have arrived") { kernel.updates.isNotEmpty() }
            val first = kernel.updates.first()
            assertEquals("console", first.surfaceId)
            assertEquals(
                WidgetUpdate.UpdateCase.FULL_TREE,
                first.updateCase,
                "the kernel logs an update with neither oneof arm set as malformed",
            )
        }

    @Test
    fun `a registered initial tree is what binds the stream`() =
        runBlocking {
            // Rather than an empty tree that would blank a surface the kernel already rendered at
            // RegisterUI.
            val tree = WidgetTree.newBuilder().setRootId("root").setVersion(7).build()
            val kernel = FakeKernelUi()
            start(kernel).registerSurface(panel(tree = tree))

            awaitTrue("a binding update should have arrived") { kernel.updates.isNotEmpty() }
            assertEquals("root", kernel.updates.first().fullTree.rootId)
        }

    @Test
    fun `user events come back down the same call`() =
        runBlocking {
            val kernel = FakeKernelUi()
            val uiClient = start(kernel)
            val received = Channel<UIEvent>(Channel.BUFFERED)
            scope.launch { uiClient.uiEvents.collect { received.send(it) } }

            uiClient.registerSurface(panel())
            awaitTrue("the stream should be open before the kernel pushes") { kernel.streamsOpened.isNotEmpty() }

            kernel.outbound.emit(
                UIEvent.newBuilder().setSurfaceId("console").setTargetNodeId("refresh-button").build(),
            )

            val event = withTimeout(WAIT_MS) { received.receive() }
            assertEquals("console", event.surfaceId)
            assertEquals("refresh-button", event.targetNodeId)
        }

    @Test
    fun `widget updates from plugin code reach the kernel`() =
        runBlocking {
            val kernel = FakeKernelUi()
            val uiClient = start(kernel)
            uiClient.registerSurface(panel())
            awaitTrue("the stream should be open") { kernel.streamsOpened.isNotEmpty() }

            uiClient.sendWidgetUpdate(
                WidgetUpdate
                    .newBuilder()
                    .setSurfaceId("console")
                    .setFullTree(WidgetTree.newBuilder().setRootId("second").setVersion(2).build())
                    .build(),
            )

            awaitTrue("the pushed tree should have arrived") {
                kernel.updates.any { it.fullTree.rootId == "second" }
            }
        }

    @Test
    fun `an update for a surface that was never registered is dropped, not sent`() =
        runBlocking {
            val kernel = FakeKernelUi()
            val uiClient = start(kernel)

            uiClient.sendWidgetUpdate(WidgetUpdate.newBuilder().setSurfaceId("ghost").build())

            delay(SETTLE_MS)
            assertTrue(kernel.updates.isEmpty(), "nothing should be sent for an unregistered surface")
            assertTrue(kernel.streamsOpened.isEmpty(), "no stream should be opened for it either")
        }

    @Test
    fun `a refused registration does not open a stream`() =
        runBlocking {
            // The kernel refuses a surface id another process holds, and will keep refusing it.
            val kernel = FakeKernelUi(accept = false)
            start(kernel).registerSurface(panel())

            awaitTrue("the registration should have been attempted") { kernel.registered.isNotEmpty() }
            delay(SETTLE_MS)
            assertTrue(kernel.streamsOpened.isEmpty(), "a refused surface must not be streamed")
        }

    @Test
    fun `unregistering tells the kernel`() =
        runBlocking {
            val kernel = FakeKernelUi()
            val uiClient = start(kernel)
            uiClient.registerSurface(panel())
            awaitTrue("the stream should be open") { kernel.streamsOpened.isNotEmpty() }

            uiClient.unregisterSurface("console")

            assertEquals(listOf("console"), kernel.unregistered.toList())
            assertTrue(uiClient.registrations().isEmpty(), "the surface should be forgotten locally too")
        }

    @Test
    fun `a tree pushed while the stream is still opening still arrives`() =
        runBlocking {
            // Registration is a round trip, so plugin code that registers and immediately renders is
            // pushing into a stream that does not exist yet. That tree is the surface's first render;
            // dropping it leaves the panel blank until something else happens to change.
            val kernel = FakeKernelUi()
            val uiClient = start(kernel)
            uiClient.registerSurface(panel())
            uiClient.sendWidgetUpdate(
                WidgetUpdate
                    .newBuilder()
                    .setSurfaceId("console")
                    .setFullTree(WidgetTree.newBuilder().setRootId("first-render").setVersion(1).build())
                    .build(),
            )

            awaitTrue("the first render should reach the kernel") {
                kernel.updates.any { it.fullTree.rootId == "first-render" }
            }
        }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L

        /** Long enough for a stream that should never open to have opened, short enough to run often. */
        const val SETTLE_MS = 400L
    }
}
