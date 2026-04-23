package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogSourceData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class ConsoleState(
    val logs: List<ConsoleLogEntry> = emptyList(),
    val filter: ConsoleLogFilter = ConsoleLogFilter.ALL,
    val searchQuery: String = "",
    val autoScroll: Boolean = true,
)

@Serializable
data class ConsoleLogEntry(
    val timestamp: Long,
    val message: String,
    val source: ConsoleLogSource,
)

@Serializable
enum class ConsoleLogSource { STDOUT, STDERR }

@Serializable
enum class ConsoleLogFilter { ALL, STDOUT, STDERR }

// endregion

// region Intent

sealed class ConsoleIntent {
    data class SetFilter(val filter: ConsoleLogFilter) : ConsoleIntent()
    data class SetSearchQuery(val query: String) : ConsoleIntent()
    object ToggleAutoScroll : ConsoleIntent()
    object ClearLogs : ConsoleIntent()
    data class NewLogs(val entries: List<ConsoleLogEntry>) : ConsoleIntent()
}

// endregion

// region Effect

sealed class ConsoleEffect {
    data class ExportResult(val text: String) : ConsoleEffect()
}

// endregion

/**
 * StateHolder for the Console/Log Viewer plugin.
 *
 * Manages log entries with filtering by source (stdout/stderr) and free-text search.
 * When new logs arrive they are appended and the active filter + search query are applied
 * so the stored [ConsoleState.logs] always represents the full unfiltered list, while
 * consumers can derive a filtered view from the state.
 *
 * **Out-of-process mode**: Use the (CoroutineScope, RemotePluginContext) constructor.
 * The StateHolder will collect logs from the kernel's LogDataProvider via gRPC proxy
 * and feed them into the state automatically.
 */
class ConsoleStateHolder : PluginStateHolder<ConsoleState, ConsoleIntent, ConsoleEffect> {

    private val logger = LoggerFactory.getLogger(ConsoleStateHolder::class.java)

    /** Backing store of all log entries before any filter/search is applied. Thread-safe. */
    private val allLogs = java.util.concurrent.CopyOnWriteArrayList<ConsoleLogEntry>()
    private val maxLogEntries = 10_000

    /**
     * In-process constructor — state management only, no auto log collection.
     * Logs are fed via [ConsoleIntent.NewLogs] intents.
     */
    constructor(scope: CoroutineScope) : super(ConsoleState(), scope)

    /**
     * Out-of-process constructor — auto-collects logs from the kernel's LogDataProvider
     * via gRPC proxy. Used when the plugin runs in a child JVM process.
     */
    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(ConsoleState(), scope) {
        val logProvider = context.logDataProvider
        logger.info("ConsoleStateHolder wiring LogDataProvider for out-of-process log collection")

        // Collect logs from the kernel's LogDataProvider proxy and feed into state
        scope.launch {
            var lastSize = 0
            logProvider.logs.collect { logEntries ->
                if (logEntries.size > lastSize) {
                    // Only process new entries (delta)
                    val newEntries = logEntries.subList(lastSize, logEntries.size)
                    val converted = newEntries.map { entry ->
                        ConsoleLogEntry(
                            timestamp = entry.timestamp,
                            message = entry.message,
                            source = when (entry.source) {
                                LogSourceData.STDOUT -> ConsoleLogSource.STDOUT
                                LogSourceData.STDERR -> ConsoleLogSource.STDERR
                            },
                        )
                    }
                    onIntent(ConsoleIntent.NewLogs(converted))
                    lastSize = logEntries.size
                }
            }
        }
    }

    override fun onIntent(intent: ConsoleIntent) {
        when (intent) {
            is ConsoleIntent.SetFilter -> {
                updateState { copy(filter = intent.filter, logs = filterLogs(allLogs, intent.filter, searchQuery)) }
            }

            is ConsoleIntent.SetSearchQuery -> {
                updateState { copy(searchQuery = intent.query, logs = filterLogs(allLogs, filter, intent.query)) }
            }

            is ConsoleIntent.ToggleAutoScroll -> {
                updateState { copy(autoScroll = !autoScroll) }
            }

            is ConsoleIntent.ClearLogs -> {
                allLogs.clear()
                updateState { copy(logs = emptyList()) }
            }

            is ConsoleIntent.NewLogs -> {
                allLogs.addAll(intent.entries)
                // Cap at maxLogEntries to prevent unbounded growth
                while (allLogs.size > maxLogEntries) {
                    allLogs.removeAt(0)
                }
                updateState { copy(logs = filterLogs(allLogs, filter, searchQuery)) }
            }
        }
    }

    private fun filterLogs(
        entries: List<ConsoleLogEntry>,
        filter: ConsoleLogFilter,
        query: String,
    ): List<ConsoleLogEntry> {
        var result = entries
        if (filter != ConsoleLogFilter.ALL) {
            val source = when (filter) {
                ConsoleLogFilter.STDOUT -> ConsoleLogSource.STDOUT
                ConsoleLogFilter.STDERR -> ConsoleLogSource.STDERR
                else -> null
            }
            if (source != null) {
                result = result.filter { it.source == source }
            }
        }
        if (query.isNotEmpty()) {
            result = result.filter { it.message.contains(query, ignoreCase = true) }
        }
        return result
    }
}
