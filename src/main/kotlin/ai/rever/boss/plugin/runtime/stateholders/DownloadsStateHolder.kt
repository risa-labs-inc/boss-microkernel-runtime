package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.DownloadItemData
import ai.rever.boss.plugin.api.DownloadStatusData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class DownloadsState(
    val downloads: List<DownloadEntry> = emptyList(),
)

@Serializable
data class DownloadEntry(
    val id: String,
    val fileName: String,
    val destinationPath: String,
    val url: String,
    val status: DownloadStatus,
    val receivedBytes: Long,
    val totalBytes: Long,
    val speed: Double,
    val canPause: Boolean,
    val canResume: Boolean,
    val errorReason: String? = null,
    val startTime: Long,
    val endTime: Long? = null,
)

@Serializable
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

// endregion

// region Intent

sealed class DownloadsIntent {
    data class Pause(val id: String) : DownloadsIntent()
    data class Resume(val id: String) : DownloadsIntent()
    data class Cancel(val id: String) : DownloadsIntent()
    data class Remove(val id: String) : DownloadsIntent()
    object ClearCompleted : DownloadsIntent()
    data class RevealInFolder(val path: String) : DownloadsIntent()
    data class OpenFile(val path: String) : DownloadsIntent()
    data class DownloadsUpdated(val downloads: List<DownloadEntry>) : DownloadsIntent()
}

// endregion

// region Effect

sealed class DownloadsEffect {
    data class OperationResult(val success: Boolean, val message: String) : DownloadsEffect()
}

// endregion

/**
 * StateHolder for the Downloads plugin.
 *
 * Tracks download entries pushed from the host via [DownloadsIntent.DownloadsUpdated].
 * Action intents (Pause, Resume, Cancel, Remove, RevealInFolder, OpenFile) are recorded
 * for the proxy bridge layer to handle via gRPC; the bridge dispatches effects back to
 * signal outcomes.
 */
class DownloadsStateHolder :
    PluginStateHolder<DownloadsState, DownloadsIntent, DownloadsEffect> {

    private val logger = LoggerFactory.getLogger(DownloadsStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(DownloadsState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(DownloadsState(), scope) {
        val provider = context.downloadDataProvider
        logger.info("DownloadsStateHolder wiring DownloadDataProvider")

        scope.launch {
            provider.downloads.collect { items ->
                val converted = items.map { it.toEntry() }
                onIntent(DownloadsIntent.DownloadsUpdated(converted))
            }
        }
    }

    private fun DownloadItemData.toEntry() = DownloadEntry(
        id = id,
        fileName = fileName,
        destinationPath = destinationPath,
        url = url,
        status = when (status) {
            DownloadStatusData.QUEUED -> DownloadStatus.QUEUED
            DownloadStatusData.DOWNLOADING -> DownloadStatus.DOWNLOADING
            DownloadStatusData.PAUSED -> DownloadStatus.PAUSED
            DownloadStatusData.COMPLETED -> DownloadStatus.COMPLETED
            DownloadStatusData.FAILED -> DownloadStatus.FAILED
            DownloadStatusData.CANCELLED -> DownloadStatus.CANCELLED
        },
        receivedBytes = receivedBytes,
        totalBytes = totalBytes ?: 0L,
        speed = speed,
        canPause = canPause,
        canResume = canResume,
        errorReason = errorReason,
        startTime = startTime,
        endTime = endTime,
    )

    override fun onIntent(intent: DownloadsIntent) {
        when (intent) {
            is DownloadsIntent.DownloadsUpdated -> {
                updateState { copy(downloads = intent.downloads) }
            }

            is DownloadsIntent.ClearCompleted -> {
                updateState {
                    copy(downloads = downloads.filter { it.status != DownloadStatus.COMPLETED })
                }
            }

            is DownloadsIntent.Remove -> {
                updateState {
                    copy(downloads = downloads.filter { it.id != intent.id })
                }
            }

            is DownloadsIntent.Pause -> {
                // Action intent — proxy bridge layer pauses the download via JxBrowser API.
            }

            is DownloadsIntent.Resume -> {
                // Action intent — proxy bridge layer resumes the download via JxBrowser API.
            }

            is DownloadsIntent.Cancel -> {
                // Action intent — proxy bridge layer cancels the download via JxBrowser API.
            }

            is DownloadsIntent.RevealInFolder -> {
                // Action intent — proxy bridge layer opens the folder in the native file manager.
            }

            is DownloadsIntent.OpenFile -> {
                // Action intent — proxy bridge layer opens the file with the system default app.
            }
        }
    }
}
