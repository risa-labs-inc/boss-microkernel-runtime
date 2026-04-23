package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.RunConfigurationData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class RunConfigurationsState(
    val configurations: List<RunConfigEntry> = emptyList(),
    val isScanning: Boolean = false,
    val lastError: String? = null,
)

@Serializable
data class RunConfigEntry(
    val id: String,
    val name: String,
    val type: String,
    val filePath: String,
    val lineNumber: Int,
    val language: String,
    val command: String,
    val workingDirectory: String,
    val arguments: String = "",
    val isAutoDetected: Boolean = true,
)

// endregion

// region Intent

sealed class RunConfigurationsIntent {
    data class ScanProject(val projectPath: String, val windowId: String) : RunConfigurationsIntent()
    data class Execute(val configId: String, val windowId: String) : RunConfigurationsIntent()
    object ClearError : RunConfigurationsIntent()
    data class ConfigurationsUpdated(val configs: List<RunConfigEntry>) : RunConfigurationsIntent()
    data class ScanningChanged(val scanning: Boolean) : RunConfigurationsIntent()
    data class ErrorChanged(val error: String?) : RunConfigurationsIntent()
}

// endregion

/**
 * StateHolder for the Run Configurations plugin.
 *
 * Based on [RunConfigurationDataProvider]. Manages a list of detected run configurations
 * for the current project. Data update intents replace the configuration list or update
 * scanning/error flags. Action intents (ScanProject, Execute) are forwarded by the proxy
 * bridge to the kernel via gRPC.
 */
class RunConfigurationsStateHolder :
    PluginStateHolder<RunConfigurationsState, RunConfigurationsIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(RunConfigurationsStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(RunConfigurationsState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(RunConfigurationsState(), scope) {
        val provider = context.runConfigurationDataProvider
        logger.info("RunConfigurationsStateHolder wiring RunConfigurationDataProvider")

        scope.launch {
            provider.detectedConfigurations.collect { configs ->
                val converted = configs.map { it.toEntry() }
                onIntent(RunConfigurationsIntent.ConfigurationsUpdated(converted))
            }
        }

        scope.launch {
            provider.isScanning.collect { scanning ->
                onIntent(RunConfigurationsIntent.ScanningChanged(scanning))
            }
        }

        scope.launch {
            provider.lastError.collect { error ->
                onIntent(RunConfigurationsIntent.ErrorChanged(error))
            }
        }

        // Trigger an initial scan if projectPath and windowId are available
        val projectPath = context.projectPath
        val windowId = context.windowId
        if (projectPath != null && windowId != null) {
            scope.launch {
                provider.scanProject(projectPath, windowId)
            }
        }
    }

    private fun RunConfigurationData.toEntry() = RunConfigEntry(
        id = id,
        name = name,
        type = type.name,
        filePath = filePath,
        lineNumber = lineNumber,
        language = language.displayName,
        command = command,
        workingDirectory = workingDirectory,
        arguments = arguments,
        isAutoDetected = isAutoDetected,
    )

    override fun onIntent(intent: RunConfigurationsIntent) {
        when (intent) {
            is RunConfigurationsIntent.ConfigurationsUpdated -> {
                updateState { copy(configurations = intent.configs) }
            }

            is RunConfigurationsIntent.ScanningChanged -> {
                updateState { copy(isScanning = intent.scanning) }
            }

            is RunConfigurationsIntent.ErrorChanged -> {
                updateState { copy(lastError = intent.error) }
            }

            is RunConfigurationsIntent.ClearError -> {
                updateState { copy(lastError = null) }
            }

            is RunConfigurationsIntent.ScanProject -> {
                updateState { copy(isScanning = true, lastError = null) }
                // Action intent — proxy bridge layer triggers project scanning via gRPC.
            }

            is RunConfigurationsIntent.Execute -> {
                // Action intent — proxy bridge layer executes the configuration via gRPC.
            }
        }
    }
}
