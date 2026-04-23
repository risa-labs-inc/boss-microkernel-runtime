package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class PerformanceState(
    val currentSnapshot: PerformanceSnapshot? = null,
    val history: List<PerformanceSnapshot> = emptyList(),
    val settings: PerformanceSettings = PerformanceSettings(),
)

@Serializable
data class PerformanceSnapshot(
    val timestamp: Long,
    val heapUsedMB: Float,
    val heapMaxMB: Float,
    val heapUsagePercent: Float,
    val processLoadPercent: Float,
    val systemLoadPercent: Float,
    val activeThreadCount: Int,
    val gcCollectionCount: Long,
    val gcCollectionTimeMs: Long,
    val browserTabCount: Int,
    val terminalCount: Int,
    val editorTabCount: Int,
    val panelCount: Int,
    val windowCount: Int,
)

@Serializable
data class PerformanceSettings(
    val enabled: Boolean = true,
    val showIndicator: Boolean = true,
    val memoryWarningPercent: Int = 75,
    val memoryCriticalPercent: Int = 90,
    val cpuWarningPercent: Int = 70,
    val cpuCriticalPercent: Int = 90,
)

// endregion

// region Intent

sealed class PerformanceIntent {
    object RequestGC : PerformanceIntent()
    object ExportMetrics : PerformanceIntent()
    data class UpdateSettings(val settings: PerformanceSettings) : PerformanceIntent()
    data class SnapshotReceived(val snapshot: PerformanceSnapshot) : PerformanceIntent()
    data class HistoryUpdated(val history: List<PerformanceSnapshot>) : PerformanceIntent()
}

// endregion

// region Effect

sealed class PerformanceEffect {
    data class MetricsExported(val filePath: String) : PerformanceEffect()
    data class ExportFailed(val error: String) : PerformanceEffect()
}

// endregion

/**
 * StateHolder for the Performance Monitor plugin.
 *
 * Receives periodic [PerformanceSnapshot]s from the host and maintains a rolling history.
 * Action intents like [PerformanceIntent.RequestGC] and [PerformanceIntent.ExportMetrics]
 * are recorded for the proxy bridge layer to fulfil via gRPC; they emit effects to signal
 * completion or failure back to the UI.
 */
class PerformanceStateHolder :
    PluginStateHolder<PerformanceState, PerformanceIntent, PerformanceEffect> {

    private val logger = LoggerFactory.getLogger(PerformanceStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(PerformanceState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(PerformanceState(), scope) {
        val provider = context.performanceDataProvider
        logger.info("PerformanceStateHolder wiring PerformanceDataProvider")

        scope.launch {
            provider.currentSnapshot.collect { data ->
                data?.let {
                    onIntent(PerformanceIntent.SnapshotReceived(it.toSnapshot()))
                }
            }
        }

        scope.launch {
            provider.history.collect { data ->
                onIntent(PerformanceIntent.HistoryUpdated(data.map { it.toSnapshot() }))
            }
        }

        scope.launch {
            provider.settings.collect { data ->
                onIntent(PerformanceIntent.UpdateSettings(data.toSettings()))
            }
        }
    }

    private fun PerformanceSnapshotData.toSnapshot() = PerformanceSnapshot(
        timestamp = timestamp,
        heapUsedMB = heapUsedMB,
        heapMaxMB = heapMaxMB,
        heapUsagePercent = heapUsagePercent,
        processLoadPercent = processLoadPercent,
        systemLoadPercent = systemLoadPercent,
        activeThreadCount = activeThreadCount,
        gcCollectionCount = gcCollectionCount,
        gcCollectionTimeMs = gcCollectionTimeMs,
        browserTabCount = browserTabCount,
        terminalCount = terminalCount,
        editorTabCount = editorTabCount,
        panelCount = panelCount,
        windowCount = windowCount,
    )

    private fun PerformanceSettingsData.toSettings() = PerformanceSettings(
        enabled = enabled,
        showIndicator = showIndicator,
        memoryWarningPercent = memoryWarningThresholdPercent,
        memoryCriticalPercent = memoryCriticalThresholdPercent,
        cpuWarningPercent = cpuWarningThresholdPercent,
        cpuCriticalPercent = cpuCriticalThresholdPercent,
    )

    override fun onIntent(intent: PerformanceIntent) {
        when (intent) {
            is PerformanceIntent.SnapshotReceived -> {
                updateState { copy(currentSnapshot = intent.snapshot) }
            }

            is PerformanceIntent.HistoryUpdated -> {
                updateState { copy(history = intent.history) }
            }

            is PerformanceIntent.UpdateSettings -> {
                updateState { copy(settings = intent.settings) }
            }

            is PerformanceIntent.RequestGC -> {
                // Action intent — the proxy bridge layer intercepts this and triggers System.gc()
                // on the host JVM, then sends back an updated snapshot.
            }

            is PerformanceIntent.ExportMetrics -> {
                // Action intent — the proxy bridge layer handles the actual file export.
                // On completion it dispatches a MetricsExported or ExportFailed effect.
            }
        }
    }
}
