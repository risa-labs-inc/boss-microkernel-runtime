package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.PluginIntentEnvelope
import ai.rever.boss.ipc.proto.PluginStateUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginStateDeltaStreamTest {

    private data class CounterState(
        val count: Int = 0,
        val padding: String = "x".repeat(4096),
    )

    private class CounterHolder(scope: CoroutineScope) :
        PluginStateHolder<CounterState, Unit, Nothing>(
            CounterState(),
            scope,
        ) {
        override fun onIntent(intent: Unit) {
            updateState { copy(count = count + 1) }
        }
    }

    private fun service(
        holder: CounterHolder,
        scope: CoroutineScope,
    ): PluginStateSyncService<CounterState, Unit> =
        PluginStateSyncService(
            pluginId = "delta-test",
            instanceId = "instance-1",
            stateHolder = holder,
            serializeState = { state ->
                // Controlled fixture: padding contains only the character x.
                """{"count":${state.count},"padding":"${state.padding}"}"""
                    .toByteArray(Charsets.UTF_8)
            },
            deserializeIntent = { _, _ -> null },
            stateTypeName = "CounterState",
            scope = scope,
        )

    @Test
    fun `small change sends a delta after the initial full state`() = runTest {
        val holder = CounterHolder(this)
        val service = service(holder, this)

        val updates = service
            .syncState(emptyFlow<PluginIntentEnvelope>())
            .onEach { update ->
                if (update.hasFullState() && update.fullState.version == 0L) {
                    holder.onIntent(Unit)
                }
            }
            .take(2)
            .toList()

        assertTrue(updates[0].hasFullState())
        assertEquals(0L, updates[0].fullState.version)

        assertTrue(
            updates[1].hasDeltaState(),
            "A small field change should produce a delta",
        )

        val delta = updates[1].deltaState
        assertEquals("delta-test", delta.pluginId)
        assertEquals("instance-1", delta.instanceId)
        assertEquals(0L, delta.baseVersion)
        assertEquals(1L, delta.newVersion)
        assertEquals("""{"count":1}""", delta.patchBytes.toStringUtf8())

        assertTrue(
            updates[1].serializedSize < updates[0].serializedSize,
            "The delta message should be smaller than the full snapshot",
        )
    }

    @Test
    fun `each subscription starts with a fresh full state`() = runTest {
        val holder = CounterHolder(this)
        val service = service(holder, this)

        suspend fun collectTwoUpdates(): List<PluginStateUpdate> {
            var received = 0

            return service
                .syncState(emptyFlow<PluginIntentEnvelope>())
                .onEach {
                    received += 1
                    if (received == 1) {
                        holder.onIntent(Unit)
                    }
                }
                .take(2)
                .toList()
        }

        val firstConnection = collectTwoUpdates()
        val secondConnection = collectTwoUpdates()

        assertTrue(firstConnection[0].hasFullState())
        assertEquals(0L, firstConnection[0].fullState.version)
        assertTrue(firstConnection[1].hasDeltaState())
        assertEquals(0L, firstConnection[1].deltaState.baseVersion)
        assertEquals(1L, firstConnection[1].deltaState.newVersion)

        assertTrue(
            secondConnection[0].hasFullState(),
            "A new subscription must receive a full snapshot first",
        )
        assertEquals(1L, secondConnection[0].fullState.version)
        assertTrue(secondConnection[1].hasDeltaState())
        assertEquals(1L, secondConnection[1].deltaState.baseVersion)
        assertEquals(2L, secondConnection[1].deltaState.newVersion)
    }
}