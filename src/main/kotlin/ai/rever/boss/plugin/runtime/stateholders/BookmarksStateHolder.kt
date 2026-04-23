package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class BookmarksState(
    val collections: List<BookmarkCollectionEntry> = emptyList(),
    val favoriteWorkspaces: List<FavoriteWorkspaceEntry> = emptyList(),
    val expandedCollections: Set<String> = emptySet(),
)

@Serializable
data class BookmarkCollectionEntry(
    val id: String,
    val name: String,
    val bookmarks: List<BookmarkEntry> = emptyList(),
)

@Serializable
data class BookmarkEntry(
    val id: String,
    val title: String,
    val url: String? = null,
    val filePath: String? = null,
    val tabType: String = "browser",
)

@Serializable
data class FavoriteWorkspaceEntry(
    val workspaceId: String,
    val workspaceName: String,
)

// endregion

// region Intent

sealed class BookmarksIntent {
    data class OpenBookmark(val collectionId: String, val bookmarkId: String) : BookmarksIntent()
    data class RemoveBookmark(val collectionId: String, val bookmarkId: String) : BookmarksIntent()
    data class CreateCollection(val name: String) : BookmarksIntent()
    data class DeleteCollection(val collectionId: String) : BookmarksIntent()
    data class RenameCollection(val collectionId: String, val newName: String) : BookmarksIntent()
    data class ToggleCollectionExpanded(val collectionId: String) : BookmarksIntent()
    data class CollectionsUpdated(val collections: List<BookmarkCollectionEntry>) : BookmarksIntent()
    data class FavoritesUpdated(val favorites: List<FavoriteWorkspaceEntry>) : BookmarksIntent()
    data class AddFavoriteWorkspace(val id: String, val name: String) : BookmarksIntent()
    data class RemoveFavoriteWorkspace(val id: String) : BookmarksIntent()
}

// endregion

// region Effect

sealed class BookmarksEffect {
    data class OpenUrl(val url: String, val title: String) : BookmarksEffect()
    data class OpenFile(val filePath: String, val fileName: String) : BookmarksEffect()
}

// endregion

/**
 * StateHolder for the Bookmarks plugin.
 *
 * Manages bookmark collections and favorite workspaces. Data update intents
 * ([BookmarksIntent.CollectionsUpdated], [BookmarksIntent.FavoritesUpdated]) replace
 * their respective state slices. Local intents (ToggleCollectionExpanded) update UI state
 * directly. Action intents (OpenBookmark, RemoveBookmark, CreateCollection, etc.) are
 * forwarded by the proxy bridge to the kernel via gRPC. Effects signal navigation actions.
 */
class BookmarksStateHolder :
    PluginStateHolder<BookmarksState, BookmarksIntent, BookmarksEffect> {

    private val logger = LoggerFactory.getLogger(BookmarksStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(BookmarksState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(BookmarksState(), scope) {
        logger.warn("BookmarksStateHolder: BookmarkDataProvider is not yet available in RemotePluginContext. " +
                "Bookmarks will not be automatically synchronized.")
    }

    override fun onIntent(intent: BookmarksIntent) {
        when (intent) {
            is BookmarksIntent.CollectionsUpdated -> {
                updateState { copy(collections = intent.collections) }
            }

            is BookmarksIntent.FavoritesUpdated -> {
                updateState { copy(favoriteWorkspaces = intent.favorites) }
            }

            is BookmarksIntent.ToggleCollectionExpanded -> {
                updateState {
                    val updated = if (intent.collectionId in expandedCollections) {
                        expandedCollections - intent.collectionId
                    } else {
                        expandedCollections + intent.collectionId
                    }
                    copy(expandedCollections = updated)
                }
            }

            is BookmarksIntent.RemoveBookmark -> {
                updateState {
                    copy(
                        collections = collections.map { collection ->
                            if (collection.id == intent.collectionId) {
                                collection.copy(bookmarks = collection.bookmarks.filter { it.id != intent.bookmarkId })
                            } else {
                                collection
                            }
                        }
                    )
                }
                // Action intent — proxy bridge layer persists the removal via gRPC.
            }

            is BookmarksIntent.DeleteCollection -> {
                updateState {
                    copy(collections = collections.filter { it.id != intent.collectionId })
                }
                // Action intent — proxy bridge layer persists the deletion via gRPC.
            }

            is BookmarksIntent.RemoveFavoriteWorkspace -> {
                updateState {
                    copy(favoriteWorkspaces = favoriteWorkspaces.filter { it.workspaceId != intent.id })
                }
                // Action intent — proxy bridge layer persists the removal via gRPC.
            }

            is BookmarksIntent.AddFavoriteWorkspace -> {
                updateState {
                    copy(
                        favoriteWorkspaces = favoriteWorkspaces + FavoriteWorkspaceEntry(
                            workspaceId = intent.id,
                            workspaceName = intent.name,
                        )
                    )
                }
                // Action intent — proxy bridge layer persists the addition via gRPC.
            }

            is BookmarksIntent.OpenBookmark -> {
                // Action intent — proxy bridge layer opens the bookmark via gRPC.
                // Find the bookmark and emit the appropriate effect.
                val state = currentState()
                val bookmark = state.collections
                    .find { it.id == intent.collectionId }
                    ?.bookmarks
                    ?.find { it.id == intent.bookmarkId }
                if (bookmark != null) {
                    when {
                        bookmark.url != null -> emitEffect(BookmarksEffect.OpenUrl(bookmark.url, bookmark.title))
                        bookmark.filePath != null -> emitEffect(BookmarksEffect.OpenFile(bookmark.filePath, bookmark.title))
                    }
                }
            }

            is BookmarksIntent.CreateCollection -> {
                // Action intent — proxy bridge layer creates the collection via gRPC.
            }

            is BookmarksIntent.RenameCollection -> {
                updateState {
                    copy(
                        collections = collections.map { collection ->
                            if (collection.id == intent.collectionId) {
                                collection.copy(name = intent.newName)
                            } else {
                                collection
                            }
                        }
                    )
                }
                // Action intent — proxy bridge layer persists the rename via gRPC.
            }
        }
    }
}
