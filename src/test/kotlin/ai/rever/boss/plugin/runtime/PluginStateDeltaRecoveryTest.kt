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

class PluginStateDeltaRecoveryTest {
    private class Holder(initial: String, scope: CoroutineScope) :
        PluginStateHolder<String, String, Nothing>(initial, scope) {
        override fun onIntent(intent: String) {
            updateState { intent }
        }
    }

    private fun document(count: Int): String =
        """{"count":$count,"padding":"${"x".repeat(4096)}"}"""

    private fun service(
        holder: Holder,
        scope: CoroutineScope,
        serialize: (String) -> ByteArray = {
            it.toByteArray(Charsets.UTF_8)
        },
    ): PluginStateSyncService<String, String> =
        PluginStateSyncService(
            pluginId = "recovery-test",
            instanceId = "instance-1",
            stateHolder = holder,
            serializeState = serialize,
            deserializeIntent = { _, _ -> null },
            stateTypeName = "TestState",
            scope = scope,
        )

    private suspend fun collectSequence(
        states: List<String>,
        scope: CoroutineScope,
    ): List<PluginStateUpdate> {
        val holder = Holder(states.first(), scope)
        var next = 1

        return service(holder, scope)
            .syncState(emptyFlow<PluginIntentEnvelope>())
            .onEach {
                if (next < states.size) {
                    holder.onIntent(states[next++])
                }
            }
            .take(states.size)
            .toList()
    }

    @Test
    fun `delta base follows emitted version when intermediate state is skipped`() =
        runTest {
            val holder = Holder(document(0), this)
            var advancedDuringSerialization = false

            val service = service(holder, this) { state ->
                if (
                    state == document(1) &&
                    !advancedDuringSerialization
                ) {
                    advancedDuringSerialization = true
                    // No suspension: version 2 is superseded before the
                    // state collector can observe it.
                    holder.onIntent(document(2))
                    holder.onIntent(document(3))
                }
                state.toByteArray(Charsets.UTF_8)
            }

            var received = 0
            val updates = service
                .syncState(emptyFlow<PluginIntentEnvelope>())
                .onEach {
                    received += 1
                    if (received == 1) {
                        holder.onIntent(document(1))
                    }
                }
                .take(3)
                .toList()

            assertTrue(updates[0].hasFullState())
            assertEquals(0L, updates[0].fullState.version)

            assertTrue(updates[1].hasDeltaState())
            assertEquals(0L, updates[1].deltaState.baseVersion)
            assertEquals(1L, updates[1].deltaState.newVersion)
            assertEquals(
                """{"count":1}""",
                updates[1].deltaState.patchBytes.toStringUtf8(),
            )

            assertTrue(updates[2].hasDeltaState())
            assertEquals(1L, updates[2].deltaState.baseVersion)
            assertEquals(3L, updates[2].deltaState.newVersion)
            assertEquals(
                """{"count":3}""",
                updates[2].deltaState.patchBytes.toStringUtf8(),
            )
        }

    @Test
    fun `non JSON state clears baseline and later JSON restores delta streaming`() =
        runTest {
            val states = listOf(
                document(0),
                "opaque-state",
                document(2),
                document(3),
            )
            val updates = collectSequence(states, this)

            for (index in 0..2) {
                assertTrue(updates[index].hasFullState())
                assertEquals(
                    index.toLong(),
                    updates[index].fullState.version,
                )
                assertEquals(
                    states[index],
                    updates[index].fullState.stateBytes.toStringUtf8(),
                )
            }

            assertTrue(updates[3].hasDeltaState())
            assertEquals(2L, updates[3].deltaState.baseVersion)
            assertEquals(3L, updates[3].deltaState.newVersion)
        }

    @Test
    fun `explicit null fallback becomes the baseline for the next delta`() =
        runTest {
            val padding = "x".repeat(4096)
            val states = listOf(
                """{"value":1,"count":0,"padding":"$padding"}""",
                """{"value":null,"count":1,"padding":"$padding"}""",
                """{"value":null,"count":2,"padding":"$padding"}""",
            )
            val updates = collectSequence(states, this)

            assertTrue(updates[0].hasFullState())
            assertTrue(updates[1].hasFullState())
            assertEquals(1L, updates[1].fullState.version)
            assertEquals(
                states[1],
                updates[1].fullState.stateBytes.toStringUtf8(),
            )

            assertTrue(updates[2].hasDeltaState())
            assertEquals(1L, updates[2].deltaState.baseVersion)
            assertEquals(2L, updates[2].deltaState.newVersion)
            assertEquals(
                """{"count":2}""",
                updates[2].deltaState.patchBytes.toStringUtf8(),
            )
        }

    @Test
    fun `patch with no payload savings uses full state`() = runTest {
        val states = listOf("{}", """{"count":1}""")
        val updates = collectSequence(states, this)

        assertTrue(updates[0].hasFullState())
        assertTrue(updates[1].hasFullState())
        assertEquals(1L, updates[1].fullState.version)
        assertEquals(
            states[1],
            updates[1].fullState.stateBytes.toStringUtf8(),
        )
    }
}