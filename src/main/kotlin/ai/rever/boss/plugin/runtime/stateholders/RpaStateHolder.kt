package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class RpaState(
    // LLM RPA
    val llmInstruction: String = "",
    val llmExecutions: List<LlmExecution> = emptyList(),
    val isLlmGenerating: Boolean = false,
    // RPA Recorder
    val isRecording: Boolean = false,
    val recordedActions: List<RpaRecordedAction> = emptyList(),
    val selectedTabId: String? = null,
    val tabs: List<RpaTabEntry> = emptyList(),
    // RPA Engine
    val engineStatus: RpaEngineStatus = RpaEngineStatus.IDLE,
    val configFiles: List<RpaConfigFile> = emptyList(),
    val executionResults: List<RpaExecutionResult> = emptyList(),
    val selectedConfigPath: String? = null,
    // Shared
    val activeTab: RpaActiveTab = RpaActiveTab.LLM_RPA,
)

@Serializable
data class RpaTabEntry(
    val id: String,
    val title: String,
    val url: String? = null,
)

@Serializable
enum class RpaActiveTab { LLM_RPA, RECORDER, ENGINE }

@Serializable
enum class RpaEngineStatus { IDLE, LOADING, EXECUTING, PAUSED, COMPLETED, ERROR }

@Serializable
data class LlmExecution(
    val instruction: String,
    val status: String,
    val generatedActions: List<RpaActionEntry> = emptyList(),
    val error: String? = null,
    val timestamp: Long = 0,
)

@Serializable
data class RpaRecordedAction(
    val type: String,
    val selector: String,
    val value: String? = null,
    val timestamp: Long = 0,
    val elementText: String? = null,
    val url: String? = null,
)

@Serializable
data class RpaActionEntry(
    val name: String,
    val type: String,
    val selector: String = "",
    val value: String = "",
    val waitMs: Int = 0,
)

@Serializable
data class RpaConfigFile(
    val name: String,
    val path: String,
    val lastModified: Long,
)

@Serializable
data class RpaExecutionResult(
    val actionIndex: Int,
    val actionName: String,
    val success: Boolean,
    val error: String? = null,
    val timestamp: Long = 0,
)

// endregion

// region Intent

sealed class RpaIntent {
    // LLM RPA
    data class SetInstruction(val instruction: String) : RpaIntent()
    object GenerateActions : RpaIntent()
    object ExecuteLlmActions : RpaIntent()
    // Recorder
    object StartRecording : RpaIntent()
    object StopRecording : RpaIntent()
    object ClearRecording : RpaIntent()
    data class SelectRecorderTab(val tabId: String) : RpaIntent()
    // Engine
    data class LoadConfig(val path: String) : RpaIntent()
    object RunEngine : RpaIntent()
    object PauseEngine : RpaIntent()
    object ResumeEngine : RpaIntent()
    object StopEngine : RpaIntent()
    data class RefreshConfigs(val directory: String) : RpaIntent()
    // Tab switching
    data class SwitchTab(val tab: RpaActiveTab) : RpaIntent()
    // State updates from providers
    data class TabsUpdated(val tabs: List<RpaTabEntry>) : RpaIntent()
    data class LlmExecutionsUpdated(val executions: List<LlmExecution>) : RpaIntent()
    data class RecordedActionsUpdated(val actions: List<RpaRecordedAction>) : RpaIntent()
    data class EngineStatusChanged(val status: RpaEngineStatus) : RpaIntent()
    data class ConfigFilesUpdated(val files: List<RpaConfigFile>) : RpaIntent()
    data class ExecutionResultsUpdated(val results: List<RpaExecutionResult>) : RpaIntent()
}

// endregion

// region Effect

sealed class RpaEffect {
    data class Error(val message: String) : RpaEffect()
    data class ActionGenerated(val actions: List<RpaActionEntry>) : RpaEffect()
}

// endregion

/**
 * StateHolder for the RPA plugin (covers llmrpa, rpaengine, and rparecorder panels).
 *
 * Manages three sub-domains:
 * - **LLM RPA**: Natural language instruction to automated browser actions.
 * - **RPA Recorder**: Records user browser interactions for replay.
 * - **RPA Engine**: Executes RPA config files with pause/resume/stop support.
 *
 * Data update intents replace their respective state slices. Action intents set local
 * loading/status flags and are forwarded by the proxy bridge to the kernel via gRPC.
 */
class RpaStateHolder :
    PluginStateHolder<RpaState, RpaIntent, RpaEffect> {

    private val logger = LoggerFactory.getLogger(RpaStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(RpaState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(RpaState(), scope) {
        val provider = context.activeTabsProvider
        logger.info("RpaStateHolder wiring ActiveTabsProvider")

        scope.launch {
            provider.activeTabs.collect { tabs ->
                val converted = tabs.map { it.toEntry() }
                onIntent(RpaIntent.TabsUpdated(converted))
            }
        }
    }

    private fun ActiveTabData.toEntry() = RpaTabEntry(
        id = tabId,
        title = title,
        url = url,
    )

    override fun onIntent(intent: RpaIntent) {
        when (intent) {
            // LLM RPA — data updates
            is RpaIntent.SetInstruction -> {
                updateState { copy(llmInstruction = intent.instruction) }
            }

            is RpaIntent.LlmExecutionsUpdated -> {
                updateState { copy(llmExecutions = intent.executions, isLlmGenerating = false) }
            }

            // LLM RPA — action intents
            is RpaIntent.GenerateActions -> {
                updateState { copy(isLlmGenerating = true) }
                // Action intent — proxy bridge layer triggers LLM generation via gRPC.
            }

            is RpaIntent.ExecuteLlmActions -> {
                updateState { copy(engineStatus = RpaEngineStatus.EXECUTING) }
                // Action intent — proxy bridge layer executes generated actions via gRPC.
            }

            // Recorder — data updates
            is RpaIntent.RecordedActionsUpdated -> {
                updateState { copy(recordedActions = intent.actions) }
            }

            // Recorder — action intents
            is RpaIntent.StartRecording -> {
                updateState { copy(isRecording = true, recordedActions = emptyList()) }
                // Action intent — proxy bridge layer starts recording via gRPC.
            }

            is RpaIntent.StopRecording -> {
                updateState { copy(isRecording = false) }
                // Action intent — proxy bridge layer stops recording via gRPC.
            }

            is RpaIntent.ClearRecording -> {
                updateState { copy(recordedActions = emptyList()) }
            }

            is RpaIntent.SelectRecorderTab -> {
                updateState { copy(selectedTabId = intent.tabId) }
                // Action intent — proxy bridge layer selects the target tab via gRPC.
            }

            // Engine — data updates
            is RpaIntent.EngineStatusChanged -> {
                updateState { copy(engineStatus = intent.status) }
            }

            is RpaIntent.ConfigFilesUpdated -> {
                updateState { copy(configFiles = intent.files) }
            }

            is RpaIntent.ExecutionResultsUpdated -> {
                updateState { copy(executionResults = intent.results) }
            }

            // Engine — action intents
            is RpaIntent.LoadConfig -> {
                updateState { copy(selectedConfigPath = intent.path, engineStatus = RpaEngineStatus.LOADING) }
                // Action intent — proxy bridge layer loads config via gRPC.
            }

            is RpaIntent.RunEngine -> {
                updateState { copy(engineStatus = RpaEngineStatus.EXECUTING, executionResults = emptyList()) }
                // Action intent — proxy bridge layer runs the engine via gRPC.
            }

            is RpaIntent.PauseEngine -> {
                updateState { copy(engineStatus = RpaEngineStatus.PAUSED) }
                // Action intent — proxy bridge layer pauses the engine via gRPC.
            }

            is RpaIntent.ResumeEngine -> {
                updateState { copy(engineStatus = RpaEngineStatus.EXECUTING) }
                // Action intent — proxy bridge layer resumes the engine via gRPC.
            }

            is RpaIntent.StopEngine -> {
                updateState { copy(engineStatus = RpaEngineStatus.IDLE) }
                // Action intent — proxy bridge layer stops the engine via gRPC.
            }

            is RpaIntent.RefreshConfigs -> {
                // Action intent — proxy bridge layer scans for config files via gRPC.
            }

            // Tab switching
            is RpaIntent.SwitchTab -> {
                updateState { copy(activeTab = intent.tab) }
            }

            // Provider updates
            is RpaIntent.TabsUpdated -> {
                updateState { copy(tabs = intent.tabs) }
            }
        }
    }
}
