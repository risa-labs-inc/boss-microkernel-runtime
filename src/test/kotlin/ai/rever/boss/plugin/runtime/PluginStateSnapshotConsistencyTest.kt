package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.PluginIntentEnvelope
import ai.rever.boss.ipc.proto.PluginStateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginStateSnapshotConsistencyTest {
    private class CounterHolder(scope: CoroutineScope) :
        PluginStateHolder<Int, Unit, Nothing>(0, scope) {
        override fun onIntent(intent: Unit) {
            updateState { this + 1 }
        }
    }

    private fun serviceUpdatingDuringSerialization(
        holder: CounterHolder,
        scope: CoroutineScope,
    ): PluginStateSyncService<Int, Unit> {
        var updated = false
        return PluginStateSyncService(
            pluginId = "snapshot-test",
            instanceId = "instance-1",
            stateHolder = holder,
            serializeState = { state ->
                // Force an update after the state was selected but before
                // serialization returns. No sleeps or timing assumptions.
                if (!updated) {
                    updated = true
                    holder.onIntent(Unit)
                }
                state.toString().toByteArray(Charsets.UTF_8)
            },
            deserializeIntent = { _, _ -> null },
            scope = scope,
        )
    }

    @Test
    fun `snapshot keeps the version belonging to its serialized state`() = runTest {
        val holder = CounterHolder(this)
        holder.onIntent(Unit)
        val service = serviceUpdatingDuringSerialization(holder, this)

        val request = PluginStateRequest.newBuilder()
            .setPluginId("snapshot-test")
            .setInstanceId("instance-1")
            .build()

        val envelope = service.getCurrentState(request)

        assertEquals(2, holder.currentState())
        assertEquals(2L, holder.version)
        assertEquals("1", envelope.stateBytes.toStringUtf8())
        assertEquals(
            1L,
            envelope.version,
            "State 1 must not be labelled with state 2's version",
        )
    }

    @Test
    fun `stream keeps the version belonging to its serialized state`() = runTest {
        val holder = CounterHolder(this)
        holder.onIntent(Unit)
        val service = serviceUpdatingDuringSerialization(holder, this)

        val update = service.syncState(emptyFlow<PluginIntentEnvelope>()).first()

        assertTrue(update.hasFullState(), "The first update must be a full snapshot")
        val envelope = update.fullState
        assertEquals(2, holder.currentState())
        assertEquals(2L, holder.version)
        assertEquals("1", envelope.stateBytes.toStringUtf8())
        assertEquals(
            1L,
            envelope.version,
            "State 1 must not be labelled with state 2's version",
        )
    }
}