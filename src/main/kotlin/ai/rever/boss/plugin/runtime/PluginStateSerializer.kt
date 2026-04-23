package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.PluginIntentEnvelope
import ai.rever.boss.ipc.proto.PluginStateEnvelope
import ai.rever.boss.ipc.proto.PluginStateServiceGrpcKt
import ai.rever.boss.ipc.proto.PluginStateRequest
import ai.rever.boss.ipc.proto.PluginStateUpdate
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * gRPC service implementation for PluginStateService that bridges a [PluginStateHolder]
 * to the kernel via the state sync protocol.
 *
 * Runs inside the plugin child process. Serializes the StateHolder's state into
 * [PluginStateEnvelope] messages and forwards intents from the kernel to the StateHolder.
 *
 * @param S The state type
 * @param I The intent type
 * @param pluginId The plugin's ID
 * @param instanceId Unique instance identifier
 * @param stateHolder The plugin's state holder
 * @param serializeState Function to serialize state to bytes (typically kotlinx.serialization JSON)
 * @param deserializeIntent Function to deserialize intent from type + bytes
 */
class PluginStateSyncService<S, I>(
    private val pluginId: String,
    private val instanceId: String,
    private val stateHolder: PluginStateHolder<S, I, *>,
    private val serializeState: (S) -> ByteArray,
    private val deserializeIntent: (intentType: String, payloadBytes: ByteArray) -> I?,
    private val stateTypeName: String = "",
    private val scope: CoroutineScope,
) : PluginStateServiceGrpcKt.PluginStateServiceCoroutineImplBase() {

    override fun syncState(requests: Flow<PluginIntentEnvelope>): Flow<PluginStateUpdate> = channelFlow {
        // Forward intents from kernel to state holder
        launch {
            requests.collect { envelope ->
                val intent = deserializeIntent(envelope.intentType, envelope.payloadBytes.toByteArray())
                if (intent != null) {
                    stateHolder.onIntent(intent)
                }
            }
        }

        // Stream state updates to kernel (runs until gRPC cancels the flow)
        stateHolder.state.collect { state ->
            val envelope = PluginStateEnvelope.newBuilder()
                .setPluginId(pluginId)
                .setInstanceId(instanceId)
                .setStateBytes(ByteString.copyFrom(serializeState(state)))
                .setVersion(stateHolder.version)
                .setTimestamp(System.currentTimeMillis())
                .setStateType(stateTypeName)
                .build()

            send(
                PluginStateUpdate.newBuilder()
                    .setFullState(envelope)
                    .build()
            )
        }
    }

    override suspend fun getCurrentState(request: PluginStateRequest): PluginStateEnvelope {
        val state = stateHolder.currentState()
        return PluginStateEnvelope.newBuilder()
            .setPluginId(pluginId)
            .setInstanceId(instanceId)
            .setStateBytes(ByteString.copyFrom(serializeState(state)))
            .setVersion(stateHolder.version)
            .setTimestamp(System.currentTimeMillis())
            .setStateType(stateTypeName)
            .build()
    }
}
