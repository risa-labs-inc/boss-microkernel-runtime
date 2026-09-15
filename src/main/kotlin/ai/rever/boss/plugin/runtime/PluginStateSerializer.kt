package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.PluginIntentEnvelope
import ai.rever.boss.ipc.proto.PluginStateDelta
import ai.rever.boss.ipc.proto.PluginStateEnvelope
import ai.rever.boss.ipc.proto.PluginStateRequest
import ai.rever.boss.ipc.proto.PluginStateServiceGrpcKt
import ai.rever.boss.ipc.proto.PluginStateUpdate
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Bridges a plugin state holder to the kernel over gRPC.
 *
 * Each subscription starts with a full snapshot. Later updates use
 * JSON Merge Patch when representable and smaller than a full message.
 * Unary snapshot requests always return full state.
 *
 * @param serializeState Serializes state to bytes. Unsupported JSON shapes
 * or non-JSON encodings continue to use full snapshots.
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

    override fun syncState(
        requests: Flow<PluginIntentEnvelope>,
    ): Flow<PluginStateUpdate> = channelFlow {
        // These belong to this collection, never to the service instance.
        var previousState: JsonObject? = null
        var previousVersion: Long? = null

        launch {
            requests.collect { envelope ->
                val intent = deserializeIntent(
                    envelope.intentType,
                    envelope.payloadBytes.toByteArray(),
                )
                if (intent != null) {
                    stateHolder.onIntent(intent)
                }
            }
        }

        stateHolder.snapshots.collect { snapshot ->
            // Serialize exactly the state belonging to snapshot.version.
            val stateBytes = serializeState(snapshot.state)
            val timestamp = System.currentTimeMillis()
            val currentState = PluginStateMergePatch.parseObject(stateBytes)

            val fullUpdate = PluginStateUpdate.newBuilder()
                .setFullState(
                    fullEnvelope(stateBytes, snapshot.version, timestamp),
                )
                .build()

            var update = fullUpdate
            val baseState = previousState
            val baseVersion = previousVersion

            if (
                baseState != null &&
                currentState != null &&
                baseVersion != null &&
                snapshot.version > baseVersion
            ) {
                val patchBytes = PluginStateMergePatch.create(
                    baseState,
                    currentState,
                )

                if (patchBytes != null && patchBytes.size < stateBytes.size) {
                    val deltaUpdate = PluginStateUpdate.newBuilder()
                        .setDeltaState(
                            PluginStateDelta.newBuilder()
                                .setPluginId(pluginId)
                                .setInstanceId(instanceId)
                                .setBaseVersion(baseVersion)
                                .setNewVersion(snapshot.version)
                                .setPatchBytes(ByteString.copyFrom(patchBytes))
                                .setTimestamp(timestamp)
                                .build(),
                        )
                        .build()

                    if (deltaUpdate.serializedSize < fullUpdate.serializedSize) {
                        update = deltaUpdate
                    }
                }
            }

            send(update)

            // Advance only after successful enqueue. This is stream ordering,
            // not an acknowledgement that the remote receiver applied it.
            // StateFlow may skip versions; the next base is still this version.
            previousState = currentState
            previousVersion = snapshot.version
        }
    }

    override suspend fun getCurrentState(
        request: PluginStateRequest,
    ): PluginStateEnvelope {
        val snapshot = stateHolder.currentSnapshot()
        return fullEnvelope(
            serializeState(snapshot.state),
            snapshot.version,
            System.currentTimeMillis(),
        )
    }

    private fun fullEnvelope(
        stateBytes: ByteArray,
        version: Long,
        timestamp: Long,
    ): PluginStateEnvelope =
        PluginStateEnvelope.newBuilder()
            .setPluginId(pluginId)
            .setInstanceId(instanceId)
            .setStateBytes(ByteString.copyFrom(stateBytes))
            .setVersion(version)
            .setTimestamp(timestamp)
            .setStateType(stateTypeName)
            .build()
}