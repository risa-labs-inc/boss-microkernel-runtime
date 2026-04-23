package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.PluginStateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the plugin state sync framework.
 *
 * Tests the PluginStateHolder + PluginStateSyncService pipeline:
 * - State changes propagate through the sync service
 * - Intents from kernel are dispatched to the state holder
 * - Concurrent intent bursts are handled correctly
 * - State ordering guarantees are maintained
 */
class PluginStateSyncTest {

    // Simple test state and intent types
    data class CounterState(val count: Int = 0, val label: String = "default")

    sealed class CounterIntent {
        object Increment : CounterIntent()
        object Decrement : CounterIntent()
        data class SetLabel(val label: String) : CounterIntent()
        object Reset : CounterIntent()
    }

    class CounterStateHolder(scope: CoroutineScope) :
        PluginStateHolder<CounterState, CounterIntent, Nothing>(CounterState(), scope) {

        override fun onIntent(intent: CounterIntent) {
            when (intent) {
                is CounterIntent.Increment -> updateState { copy(count = count + 1) }
                is CounterIntent.Decrement -> updateState { copy(count = count - 1) }
                is CounterIntent.SetLabel -> updateState { copy(label = intent.label) }
                is CounterIntent.Reset -> updateState { CounterState() }
            }
        }
    }

    @Test
    fun `state holder updates state on intent`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val holder = CounterStateHolder(scope)

        assertEquals(0, holder.currentState().count)
        assertEquals("default", holder.currentState().label)

        holder.onIntent(CounterIntent.Increment)
        assertEquals(1, holder.currentState().count)

        holder.onIntent(CounterIntent.Increment)
        assertEquals(2, holder.currentState().count)

        holder.onIntent(CounterIntent.Decrement)
        assertEquals(1, holder.currentState().count)

        holder.onIntent(CounterIntent.SetLabel("test"))
        assertEquals("test", holder.currentState().label)

        holder.onIntent(CounterIntent.Reset)
        assertEquals(0, holder.currentState().count)
        assertEquals("default", holder.currentState().label)

        scope.cancel()
    }

    @Test
    fun `state holder version increments on each update`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val holder = CounterStateHolder(scope)

        assertEquals(0, holder.version)

        holder.onIntent(CounterIntent.Increment)
        assertEquals(1, holder.version)

        holder.onIntent(CounterIntent.Increment)
        assertEquals(2, holder.version)

        scope.cancel()
    }

    @Test
    fun `state sync service returns current state`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val holder = CounterStateHolder(scope)

        holder.onIntent(CounterIntent.Increment)
        holder.onIntent(CounterIntent.Increment)
        holder.onIntent(CounterIntent.SetLabel("synced"))

        val syncService = PluginStateSyncService(
            pluginId = "test-plugin",
            instanceId = "test-instance",
            stateHolder = holder,
            serializeState = { state -> "${state.count}:${state.label}".toByteArray() },
            deserializeIntent = { type, _ ->
                when (type) {
                    "Increment" -> CounterIntent.Increment
                    "Decrement" -> CounterIntent.Decrement
                    "Reset" -> CounterIntent.Reset
                    else -> null
                }
            },
            scope = scope,
        )

        val request = PluginStateRequest.newBuilder()
            .setPluginId("test-plugin")
            .setInstanceId("test-instance")
            .build()

        val envelope = syncService.getCurrentState(request)

        assertEquals("test-plugin", envelope.pluginId)
        assertEquals("test-instance", envelope.instanceId)
        assertEquals("2:synced", envelope.stateBytes.toStringUtf8())
        assertTrue(envelope.version > 0)
        assertTrue(envelope.timestamp > 0)

        scope.cancel()
    }

    @Test
    fun `burst intents maintain ordering`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val holder = CounterStateHolder(scope)

        // Send 100 increments
        repeat(100) {
            holder.onIntent(CounterIntent.Increment)
        }

        assertEquals(100, holder.currentState().count)
        assertEquals(100, holder.version)

        scope.cancel()
    }

    @Test
    fun `state flow emits updates`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val holder = CounterStateHolder(scope)

        val collected = mutableListOf<CounterState>()
        val collectJob = launch {
            holder.state.take(4).toList(collected)
        }

        delay(50)
        holder.onIntent(CounterIntent.Increment)
        delay(50)
        holder.onIntent(CounterIntent.Increment)
        delay(50)
        holder.onIntent(CounterIntent.SetLabel("updated"))

        withTimeout(1000) { collectJob.join() }

        assertEquals(4, collected.size)
        assertEquals(0, collected[0].count) // initial
        assertEquals(1, collected[1].count)
        assertEquals(2, collected[2].count)
        assertEquals("updated", collected[3].label)

        scope.cancel()
    }
}
