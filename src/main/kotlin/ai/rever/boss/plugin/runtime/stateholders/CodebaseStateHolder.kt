package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class CodebaseState(
    val rootPath: String? = null,
    val fileTree: List<FileTreeNode> = emptyList(),
    val expandedPaths: Set<String> = emptySet(),
    val selectedPath: String? = null,
    val isLoading: Boolean = false,
    val recentProjects: List<RecentProjectEntry> = emptyList(),
)

@Serializable
data class FileTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<FileTreeNode> = emptyList(),
)

@Serializable
data class RecentProjectEntry(
    val name: String,
    val path: String,
    val lastOpened: Long,
)

// endregion

// region Intent

sealed class CodebaseIntent {
    data class SetRootPath(val path: String) : CodebaseIntent()
    data class ToggleExpanded(val path: String) : CodebaseIntent()
    data class SelectFile(val path: String) : CodebaseIntent()
    data class OpenFile(val path: String) : CodebaseIntent()
    data class CreateFile(val parentPath: String, val name: String) : CodebaseIntent()
    data class CreateFolder(val parentPath: String, val name: String) : CodebaseIntent()
    data class Delete(val path: String) : CodebaseIntent()
    data class Rename(val path: String, val newName: String) : CodebaseIntent()
    object PickDirectory : CodebaseIntent()
    data class FileTreeUpdated(val tree: List<FileTreeNode>) : CodebaseIntent()
    data class ProjectsUpdated(val projects: List<RecentProjectEntry>) : CodebaseIntent()
    data class SetLoading(val loading: Boolean) : CodebaseIntent()
}

// endregion

// region Effect

sealed class CodebaseEffect {
    data class OpenFileInEditor(val path: String, val name: String) : CodebaseEffect()
    data class Error(val message: String) : CodebaseEffect()
}

// endregion

/**
 * StateHolder for the Codebase / File Explorer plugin.
 *
 * Manages a file tree rooted at a project directory, with expand/collapse, selection,
 * and CRUD operations. Data update intents replace the file tree or recent projects list.
 * Local intents (ToggleExpanded, SelectFile) update UI state directly. Action intents
 * (OpenFile, CreateFile, CreateFolder, Delete, Rename, PickDirectory) are forwarded by
 * the proxy bridge to the kernel via gRPC.
 */
class CodebaseStateHolder :
    PluginStateHolder<CodebaseState, CodebaseIntent, CodebaseEffect> {

    private val logger = LoggerFactory.getLogger(CodebaseStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(CodebaseState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(CodebaseState(), scope) {
        val fsProvider = context.fileSystemDataProvider
        val projectProvider = context.projectDataProvider
        logger.info("CodebaseStateHolder wiring FileSystemDataProvider and ProjectDataProvider")

        scope.launch {
            projectProvider.recentProjects.collect { projects ->
                onIntent(CodebaseIntent.ProjectsUpdated(projects.map { it.toEntry() }))
            }
        }

        val rootPath = context.projectPath
        if (rootPath != null && fsProvider != null) {
            onIntent(CodebaseIntent.SetRootPath(rootPath))
            scope.launch {
                fsProvider.scanDirectory(rootPath)?.let { node ->
                    onIntent(CodebaseIntent.FileTreeUpdated(node.children.map { it.toNode() }))
                }
            }
        }
    }

    private fun ProjectData.toEntry() = RecentProjectEntry(
        name = name,
        path = path,
        lastOpened = lastOpened,
    )

    private fun FileNodeData.toNode(): FileTreeNode = FileTreeNode(
        name = name,
        path = path,
        isDirectory = isDirectory,
        children = children.map { it.toNode() },
    )

    override fun onIntent(intent: CodebaseIntent) {
        when (intent) {
            is CodebaseIntent.FileTreeUpdated -> {
                updateState { copy(fileTree = intent.tree, isLoading = false) }
            }

            is CodebaseIntent.ProjectsUpdated -> {
                updateState { copy(recentProjects = intent.projects) }
            }

            is CodebaseIntent.SetLoading -> {
                updateState { copy(isLoading = intent.loading) }
            }

            is CodebaseIntent.SetRootPath -> {
                updateState { copy(rootPath = intent.path, isLoading = true, expandedPaths = emptySet(), selectedPath = null) }
                // Action intent — proxy bridge layer loads the file tree via gRPC.
            }

            is CodebaseIntent.ToggleExpanded -> {
                updateState {
                    val updated = if (intent.path in expandedPaths) {
                        expandedPaths - intent.path
                    } else {
                        expandedPaths + intent.path
                    }
                    copy(expandedPaths = updated)
                }
            }

            is CodebaseIntent.SelectFile -> {
                updateState { copy(selectedPath = intent.path) }
            }

            is CodebaseIntent.OpenFile -> {
                val fileName = intent.path.substringAfterLast('/')
                emitEffect(CodebaseEffect.OpenFileInEditor(intent.path, fileName))
                // Action intent — proxy bridge layer opens the file in the editor via gRPC.
            }

            is CodebaseIntent.CreateFile -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer creates the file via gRPC.
            }

            is CodebaseIntent.CreateFolder -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer creates the folder via gRPC.
            }

            is CodebaseIntent.Delete -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer deletes the path via gRPC.
            }

            is CodebaseIntent.Rename -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer renames the path via gRPC.
            }

            is CodebaseIntent.PickDirectory -> {
                // Action intent — proxy bridge layer opens a directory picker via gRPC.
            }
        }
    }
}
