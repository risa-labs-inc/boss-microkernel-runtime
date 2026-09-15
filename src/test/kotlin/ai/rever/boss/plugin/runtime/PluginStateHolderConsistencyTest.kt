package ai.rever.boss.plugin.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PluginStateHolderConsistencyTest {
    private class CounterHolder(scope: CoroutineScope) :
        PluginStateHolder<Int, Unit, Nothing>(0, scope) {
        override fun onIntent(intent: Unit) {
            updateState { this + 1 }
        }

        fun set(value: Int) {
            updateState { value }
        }
    }

    @Test
    fun `state reads immediately reflect an update`() = runTest {
        val holder = CounterHolder(this)

        assertEquals(0, holder.state.value)
        assertEquals(listOf(0), holder.state.replayCache)
        assertEquals(0L, holder.version)

        holder.onIntent(Unit)

        // No scheduler advancement: these reads must update synchronously.
        assertEquals(1, holder.currentState())
        assertEquals(1, holder.state.value)
        assertEquals(listOf(1), holder.state.replayCache)
        assertEquals(1, holder.currentSnapshot().state)
        assertEquals(1L, holder.currentSnapshot().version)
    }

    @Test
    fun `equal updates advance version without duplicate state emissions`() = runTest {
        val holder = CounterHolder(this)
        val states = mutableListOf<Int>()
        val versions = mutableListOf<Long>()

        val stateCollector = backgroundScope.launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            holder.state.collect { states.add(it) }
        }
        val snapshotCollector = backgroundScope.launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            holder.snapshots.collect { versions.add(it.version) }
        }

        try {
            holder.set(0)
            runCurrent()

            holder.set(1)
            runCurrent()

            holder.set(1)
            runCurrent()

            assertEquals(listOf(0, 1), states)
            assertEquals(listOf(0L, 1L, 2L, 3L), versions)
            assertEquals(1, holder.currentState())
            assertEquals(3L, holder.version)
        } finally {
            stateCollector.cancel()
            snapshotCollector.cancel()
        }
    }

    @Test
    fun `concurrent increments preserve counts and matching snapshot versions`() = runTest {
        val holder = CounterHolder(this)
        val workers = 4
        val incrementsPerWorker = 1000

        coroutineScope {
            List(workers) {
                launch(Dispatchers.Default) {
                    repeat(incrementsPerWorker) {
                        holder.onIntent(Unit)

                        // Other workers may have advanced the counter.
                        // Whatever snapshot we observe must remain consistent.
                        val snapshot = holder.currentSnapshot()
                        assertEquals(snapshot.state.toLong(), snapshot.version)
                    }
                }
            }.joinAll()
        }

        val expected = workers * incrementsPerWorker
        val finalSnapshot = holder.currentSnapshot()
        assertEquals(expected, finalSnapshot.state)
        assertEquals(expected.toLong(), finalSnapshot.version)
        assertEquals(expected, holder.state.value)
    }
}