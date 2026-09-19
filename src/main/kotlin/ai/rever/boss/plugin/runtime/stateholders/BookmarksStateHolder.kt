package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

// region State

@Serializable
data class BookmarksState(
    val collections: List<BookmarkCollectionEntry> = emptyList(),
    val favoriteWorkspaces: List<FavoriteWorkspaceEntry> = emptyList(),
    val expandedCollections: Set<String> = emptySet(),
    /**
     * True once the holder has published its first state. No collections is a legitimate
     * answer, so this is what distinguishes "initialised, nothing synced yet" from "no state has
     * ever arrived".
     */
    val ready: Boolean = false,
    /**
     * Always false out-of-process: there is no `BookmarkDataProvider` on the wire (no
     * `bookmark.proto`), so collections reach this holder only as host-pushed
     * [BookmarksIntent.CollectionsUpdated] / [BookmarksIntent.FavoritesUpdated], and create,
     * rename and delete change this child's copy only. A renderer should not offer them as if
     * they persisted.
     */
    val persistenceAvailable: Boolean = false,
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

    constructor(scope: CoroutineScope) : super(BookmarksState(), scope) {
        // Publish an initial versioned state so a host renderer has something to apply on
        // connect. This holder was the one the rule is named after: it never called
        // updateState, stayed at version 0, and the host's strictly-newer guard dropped every
        // envelope, so the panel waited for a first state forever.
        updateState { copy(ready = true) }
    }

    constructor(scope: CoroutineScope, context: RemotePluginContext) : this(scope) {
        logger.warn(
            "BookmarksStateHolder: no BookmarkDataProvider out-of-process (windowId={}). " +
                "Publishing an empty state; the host must push collections and favorites as " +
                "CollectionsUpdated / FavoritesUpdated intents.",
            context.windowId,
        )
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

/**
 * Decode a wire intent for [BookmarksStateHolder], or null to drop it.
 *
 * Payload shapes, following the other holders' decoders:
 * - one id or name: the raw string (`ToggleCollectionExpanded`, `DeleteCollection`,
 *   `CreateCollection`, `RemoveFavoriteWorkspace`);
 * - several fields: a JSON object keyed by the intent's property names (`OpenBookmark`,
 *   `RemoveBookmark` - `collectionId`, `bookmarkId`; `RenameCollection` - `collectionId`,
 *   `newName`; `AddFavoriteWorkspace` - `id`, `name`);
 * - the two data-update intents: a JSON array in the same shape as [BookmarksState.collections]
 *   and [BookmarksState.favoriteWorkspaces] on the wire, so a host can send back exactly what it
 *   reads. These are how a host drives this holder while there is no bookmark provider.
 *
 * A malformed payload is dropped rather than applied in part: a half-decoded CollectionsUpdated
 * would replace the user's collections with a fragment of them.
 */
internal fun decodeBookmarksIntent(intentType: String, payload: String): BookmarksIntent? {
    val obj = runCatching { bookmarksIntentJson.parseToJsonElement(payload) as? JsonObject }.getOrNull()

    fun str(key: String): String? = obj?.get(key)?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    fun id(): String? = payload.takeIf { obj == null }?.trim()?.ifBlank { null }

    return when (intentType) {
        "ToggleCollectionExpanded" -> id()?.let { BookmarksIntent.ToggleCollectionExpanded(it) }
        "DeleteCollection" -> id()?.let { BookmarksIntent.DeleteCollection(it) }
        "CreateCollection" -> id()?.let { BookmarksIntent.CreateCollection(it) }
        "RemoveFavoriteWorkspace" -> id()?.let { BookmarksIntent.RemoveFavoriteWorkspace(it) }

        "OpenBookmark" -> {
            val collectionId = str("collectionId") ?: return null
            BookmarksIntent.OpenBookmark(collectionId, str("bookmarkId") ?: return null)
        }

        "RemoveBookmark" -> {
            val collectionId = str("collectionId") ?: return null
            BookmarksIntent.RemoveBookmark(collectionId, str("bookmarkId") ?: return null)
        }

        "RenameCollection" -> {
            val collectionId = str("collectionId") ?: return null
            BookmarksIntent.RenameCollection(collectionId, str("newName") ?: return null)
        }

        "AddFavoriteWorkspace" -> {
            val workspaceId = str("id") ?: return null
            BookmarksIntent.AddFavoriteWorkspace(workspaceId, str("name") ?: return null)
        }

        "CollectionsUpdated" ->
            runCatching { bookmarksIntentJson.decodeFromString<List<BookmarkCollectionEntry>>(payload) }
                .getOrNull()
                ?.let { BookmarksIntent.CollectionsUpdated(it) }

        "FavoritesUpdated" ->
            runCatching { bookmarksIntentJson.decodeFromString<List<FavoriteWorkspaceEntry>>(payload) }
                .getOrNull()
                ?.let { BookmarksIntent.FavoritesUpdated(it) }

        else -> null
    }
}

private val bookmarksIntentJson = Json { ignoreUnknownKeys = true; isLenient = true }
