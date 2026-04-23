package ai.rever.boss.plugin.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

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
 * - State is serialized and sent to the kernel via [PluginStateBridge]
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
 * @param S The serializable state type
 * @param I The intent (user action) type
 * @param E The side effect type (use [Nothing] if no effects)
 */
abstract class PluginStateHolder<S, I, E>(
    initialState: S,
    protected val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(initialState)

    /** Current state — collect this from Compose UI or state sync bridge. */
    val state: StateFlow<S> = _state.asStateFlow()

    /** Side effects channel for one-shot events the kernel should handle. */
    private val _effects = MutableStateFlow<E?>(null)
    val effects: StateFlow<E?> = _effects.asStateFlow()

    /** Current state version — incremented atomically on every state change. */
    private val _version = AtomicLong(0)
    val version: Long get() = _version.get()

    /**
     * Handle an intent (user action) from the kernel UI.
     * Subclasses implement this to update state based on the intent.
     */
    abstract fun onIntent(intent: I)

    /**
     * Update the state using a transformation function.
     * Thread-safe: uses StateFlow's atomic update.
     */
    protected fun updateState(transform: S.() -> S) {
        _state.value = transform(_state.value)
        _version.incrementAndGet()
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
    fun currentState(): S = _state.value
}
