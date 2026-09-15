package ai.rever.boss.plugin.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State and its version captured together.
 *
 * State values must be immutable after publication.
 */
internal data class VersionedPluginState<S>(
    val state: S,
    val version: Long,
)

/**
 * Exposes state values without maintaining a second mutable state store.
 *
 * Reads immediately reflect the current snapshot. Collection suppresses equal
 * state values, preserving the existing state-only StateFlow behavior.
 */
@OptIn(
    InternalCoroutinesApi::class,
    ExperimentalForInheritanceCoroutinesApi::class,
)
private class StateOnlyFlow<S>(
    private val snapshots: StateFlow<VersionedPluginState<S>>,
) : StateFlow<S> {
    override val value: S
        get() = snapshots.value.state

    override val replayCache: List<S>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<S>): Nothing {
        var emitted = false
        var previous: Any? = null

        return snapshots.collect(
            object : FlowCollector<VersionedPluginState<S>> {
                override suspend fun emit(value: VersionedPluginState<S>) {
                    if (!emitted || previous != value.state) {
                        emitted = true
                        previous = value.state
                        collector.emit(value.state)
                    }
                }
            },
        )
    }
}

/**
 * Base class for plugin state holders used in the split-brain out-of-process model.
 *
 * A StateHolder encapsulates:
 * - The plugin's full UI state as a serializable data class [S]
 * - Intent handling: user actions from the kernel UI are dispatched as intents [I]
 * - Side effects [E] that the kernel should handle (e.g., show toast, open file)
 *
 * When running out-of-process:
 * - The StateHolder lives in the child JVM
 * - State is serialized and sent to the kernel via PluginStateBridge
 * - Intents arrive from the kernel via the gRPC state sync stream
 *
 * When running in-process:
 * - The StateHolder is used directly by the Compose UI
 * - No serialization overhead
 *
 * ## Usage Pattern
 * ```kotlin
 * @Serializable
 * data class ConsoleState(
 *     val logs: List<LogEntry> = emptyList(),
 *     val filter: LogFilter = LogFilter.ALL,
 *     val searchQuery: String = "",
 *     val autoScroll: Boolean = true,
 * )
 *
 * sealed class ConsoleIntent {
 *     data class SetFilter(val filter: LogFilter) : ConsoleIntent()
 *     data class SetSearch(val query: String) : ConsoleIntent()
 *     object ToggleAutoScroll : ConsoleIntent()
 *     object ClearLogs : ConsoleIntent()
 * }
 *
 * class ConsoleStateHolder(scope: CoroutineScope) :
 *     PluginStateHolder<ConsoleState, ConsoleIntent, Nothing>(ConsoleState(), scope) {
 *
 *     override fun onIntent(intent: ConsoleIntent) {
 *         when (intent) {
 *             is ConsoleIntent.SetFilter -> updateState { copy(filter = intent.filter) }
 *             // ...
 *         }
 *     }
 * }
 * ```
 *
 * @param S The serializable state type; values must be immutable after publication
 * @param I The intent (user action) type
 * @param E The side effect type (use [Nothing] if no effects)
 */
abstract class PluginStateHolder<S, I, E>(
    initialState: S,
    protected val scope: CoroutineScope,
) {
    private val _snapshots = MutableStateFlow(
        VersionedPluginState(state = initialState, version = 0L),
    )

    /** State and version published atomically for IPC serialization. */
    internal val snapshots: StateFlow<VersionedPluginState<S>> =
        _snapshots.asStateFlow()

    /** Current state for existing UI consumers. */
    val state: StateFlow<S> = StateOnlyFlow(snapshots)

    /** Side effects for the kernel to handle. */
    private val _effects = MutableStateFlow<E?>(null)
    val effects: StateFlow<E?> = _effects.asStateFlow()

    /**
     * Current version.
     *
     * When the corresponding state is also needed, use [currentSnapshot]
     * instead of reading state and version separately.
     */
    val version: Long
        get() = _snapshots.value.version

    /** Capture the state and its corresponding version in one read. */
    internal fun currentSnapshot(): VersionedPluginState<S> =
        _snapshots.value

    /**
     * Handle an intent (user action) from the kernel UI.
     * Subclasses implement this to update state based on the intent.
     */
    abstract fun onIntent(intent: I)

    /**
     * Apply [transform] and publish state and version together atomically.
     *
     * Concurrent updates retry against the latest snapshot, preventing lost
     * updates and mismatched state/version pairs.
     *
     * [transform] may run more than once under contention. It must be pure
     * and must not mutate previously published state.
     *
     * Every successful call advances the version, including when the resulting
     * state compares equal, preserving the existing version-counter behavior.
     */
    protected fun updateState(transform: S.() -> S) {
        _snapshots.update { previous ->
            VersionedPluginState(
                state = previous.state.transform(),
                version = previous.version + 1L,
            )
        }
    }

    /**
     * Emit a side effect for the kernel to handle.
     */
    protected fun emitEffect(effect: E) {
        _effects.value = effect
    }

    /**
     * Get the current state value.
     */
    fun currentState(): S = _snapshots.value.state
}