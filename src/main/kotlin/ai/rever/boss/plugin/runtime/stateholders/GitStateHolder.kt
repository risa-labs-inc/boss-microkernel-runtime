package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class GitState(
    val fileStatus: List<GitFileEntry> = emptyList(),
    val commitLog: List<GitCommitEntry> = emptyList(),
    val isGitRepository: Boolean = false,
    val isLoading: Boolean = false,
)

@Serializable
data class GitFileEntry(
    val path: String,
    val indexStatus: String? = null,
    val workTreeStatus: String? = null,
    val isStaged: Boolean,
    val isUnstaged: Boolean,
)

@Serializable
data class GitCommitEntry(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val authorEmail: String,
    val date: Long,
    val refs: List<String>,
)

// endregion

// region Intent

sealed class GitIntent {
    object RefreshStatus : GitIntent()
    data class RefreshLog(val limit: Int = 100) : GitIntent()
    data class Stage(val filePath: String) : GitIntent()
    data class Unstage(val filePath: String) : GitIntent()
    object StageAll : GitIntent()
    object UnstageAll : GitIntent()
    data class DiscardChanges(val filePath: String) : GitIntent()
    data class CherryPick(val hash: String) : GitIntent()
    data class Revert(val hash: String) : GitIntent()
    data class Checkout(val ref: String) : GitIntent()
    data class StatusUpdated(val files: List<GitFileEntry>) : GitIntent()
    data class LogUpdated(val commits: List<GitCommitEntry>) : GitIntent()
    data class RepoStateChanged(val isGitRepo: Boolean) : GitIntent()
    data class LoadingChanged(val loading: Boolean) : GitIntent()
}

// endregion

// region Effect

sealed class GitEffect {
    data class OperationResult(val success: Boolean, val message: String) : GitEffect()
    data class OpenFile(val filePath: String, val windowId: String) : GitEffect()
}

// endregion

/**
 * StateHolder for the Git plugin (status + log).
 *
 * Data-update intents ([GitIntent.StatusUpdated], [GitIntent.LogUpdated], etc.) directly
 * update the state. Action intents (Stage, Unstage, CherryPick, Revert, Checkout, etc.)
 * are recorded for the proxy bridge layer to execute the corresponding git operations via
 * gRPC; the bridge sends back effects to report success or failure.
 */
class GitStateHolder :
    PluginStateHolder<GitState, GitIntent, GitEffect> {

    private val logger = LoggerFactory.getLogger(GitStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(GitState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(GitState(), scope) {
        val provider = context.gitDataProvider
        logger.info("GitStateHolder wiring GitDataProvider")

        scope.launch {
            provider.fileStatus.collect { files ->
                val converted = files.map { it.toEntry() }
                onIntent(GitIntent.StatusUpdated(converted))
            }
        }

        scope.launch {
            provider.commitLog.collect { commits ->
                val converted = commits.map { it.toEntry() }
                onIntent(GitIntent.LogUpdated(converted))
            }
        }

        scope.launch {
            provider.isGitRepository.collect { isRepo ->
                onIntent(GitIntent.RepoStateChanged(isRepo))
            }
        }

        scope.launch {
            provider.isLoading.collect { loading ->
                onIntent(GitIntent.LoadingChanged(loading))
            }
        }
    }

    private fun GitFileStatusData.toEntry() = GitFileEntry(
        path = path,
        indexStatus = indexStatus?.name,
        workTreeStatus = workTreeStatus?.name,
        isStaged = isStaged,
        isUnstaged = isUnstaged,
    )

    private fun GitCommitInfoData.toEntry() = GitCommitEntry(
        hash = hash,
        shortHash = shortHash,
        subject = subject,
        author = author,
        authorEmail = authorEmail,
        date = date,
        refs = refs,
    )

    override fun onIntent(intent: GitIntent) {
        when (intent) {
            // -- Data update intents --

            is GitIntent.StatusUpdated -> {
                updateState { copy(fileStatus = intent.files) }
            }

            is GitIntent.LogUpdated -> {
                updateState { copy(commitLog = intent.commits) }
            }

            is GitIntent.RepoStateChanged -> {
                updateState { copy(isGitRepository = intent.isGitRepo) }
            }

            is GitIntent.LoadingChanged -> {
                updateState { copy(isLoading = intent.loading) }
            }

            // -- Action intents (delegated to the proxy bridge layer) --

            is GitIntent.RefreshStatus -> {
                updateState { copy(isLoading = true) }
            }

            is GitIntent.RefreshLog -> {
                updateState { copy(isLoading = true) }
            }

            is GitIntent.Stage -> {
                // Action intent — proxy bridge executes `git add <filePath>`.
            }

            is GitIntent.Unstage -> {
                // Action intent — proxy bridge executes `git restore --staged <filePath>`.
            }

            is GitIntent.StageAll -> {
                // Action intent — proxy bridge executes `git add -A`.
            }

            is GitIntent.UnstageAll -> {
                // Action intent — proxy bridge executes `git reset`.
            }

            is GitIntent.DiscardChanges -> {
                // Action intent — proxy bridge executes `git checkout -- <filePath>`.
            }

            is GitIntent.CherryPick -> {
                // Action intent — proxy bridge executes `git cherry-pick <hash>`.
            }

            is GitIntent.Revert -> {
                // Action intent — proxy bridge executes `git revert <hash>`.
            }

            is GitIntent.Checkout -> {
                // Action intent — proxy bridge executes `git checkout <ref>`.
            }
        }
    }
}
