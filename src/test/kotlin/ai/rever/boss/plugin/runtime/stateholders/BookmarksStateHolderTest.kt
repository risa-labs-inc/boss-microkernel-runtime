package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.StateWireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [BookmarksStateHolder] out-of-process (boss-microkernel-runtime#7).
 *
 * The holder the "bookmarks failure mode" is named after: it never published, so it sat at
 * version 0 and the host's strictly-newer guard dropped every envelope, and it had no intent
 * decoder, so the host could not push collections in either. With no bookmark provider on the
 * wire, publishing once and accepting host-pushed data is what lets the panel render at all.
 */
class BookmarksStateHolderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun holder() = BookmarksStateHolder(scope)

    // region initial state

    @Test
    fun `publishes a versioned empty state on construction`() {
        val holder = holder()

        assertTrue(holder.version > 0, "version must advance past 0 or nothing ever renders")
        val state = holder.currentState()
        assertTrue(state.ready)
        assertEquals(emptyList(), state.collections)
        assertEquals(emptyList(), state.favoriteWorkspaces)
    }

    /** Honest capability reporting, so a renderer does not offer create/rename/delete as saved. */
    @Test
    fun `reports persistence as unavailable, on the wire too`() {
        val holder = holder()
        assertFalse(holder.currentState().persistenceAvailable)

        val wire = Json.parseToJsonElement(
            StateWireJson.encodeToString(BookmarksState.serializer(), holder.currentState()),
        ).jsonObject
        assertEquals("false", wire["persistenceAvailable"]?.jsonPrimitive?.content)
        assertEquals("true", wire["ready"]?.jsonPrimitive?.content)
    }

    // endregion

    // region host-pushed data

    @Test
    fun `a host can populate collections and favorites through the wire decoder`() {
        val holder = holder()
        val collections = decodeBookmarksIntent(
            "CollectionsUpdated",
            """[{"id":"c1","name":"Work","bookmarks":[{"id":"b1","title":"Docs","url":"https://example.com"}]}]""",
        )
        val favorites = decodeBookmarksIntent(
            "FavoritesUpdated",
            """[{"workspaceId":"w1","workspaceName":"Main"}]""",
        )

        holder.onIntent(collections!!)
        holder.onIntent(favorites!!)

        val state = holder.currentState()
        assertEquals(listOf("Work"), state.collections.map { it.name })
        assertEquals("https://example.com", state.collections.single().bookmarks.single().url)
        assertEquals(listOf(FavoriteWorkspaceEntry("w1", "Main")), state.favoriteWorkspaces)
    }

    @Test
    fun `the data-update payload is the state's own wire shape`() {
        // A host can send back exactly what it read from the state envelope.
        val entries = listOf(BookmarkCollectionEntry("c1", "Work", listOf(BookmarkEntry("b1", "Docs", url = "u"))))
        val onTheWire = StateWireJson.encodeToString(
            BookmarksState.serializer(),
            BookmarksState(collections = entries),
        )
        val collectionsJson = Json.parseToJsonElement(onTheWire).jsonObject["collections"].toString()

        assertEquals(BookmarksIntent.CollectionsUpdated(entries), decodeBookmarksIntent("CollectionsUpdated", collectionsJson))
    }

    @Test
    fun `a malformed data update is dropped, never applied in part`() {
        assertNull(decodeBookmarksIntent("CollectionsUpdated", """[{"id":"c1","name":"Work"},{"name":"no id"}]"""))
        assertNull(decodeBookmarksIntent("CollectionsUpdated", """{"collections":"nope"}"""))
        assertNull(decodeBookmarksIntent("FavoritesUpdated", "not json"))
    }

    // endregion

    // region decoder

    @Test
    fun `single-id intents take the raw string`() {
        assertEquals(BookmarksIntent.ToggleCollectionExpanded("c1"), decodeBookmarksIntent("ToggleCollectionExpanded", "c1"))
        assertEquals(BookmarksIntent.DeleteCollection("c1"), decodeBookmarksIntent("DeleteCollection", "c1"))
        assertEquals(BookmarksIntent.CreateCollection("Reading"), decodeBookmarksIntent("CreateCollection", "Reading"))
        assertEquals(BookmarksIntent.RemoveFavoriteWorkspace("w1"), decodeBookmarksIntent("RemoveFavoriteWorkspace", "w1"))
    }

    @Test
    fun `multi-field intents take a JSON object`() {
        assertEquals(
            BookmarksIntent.OpenBookmark("c1", "b1"),
            decodeBookmarksIntent("OpenBookmark", """{"collectionId":"c1","bookmarkId":"b1"}"""),
        )
        assertEquals(
            BookmarksIntent.RemoveBookmark("c1", "b1"),
            decodeBookmarksIntent("RemoveBookmark", """{"collectionId":"c1","bookmarkId":"b1"}"""),
        )
        assertEquals(
            BookmarksIntent.RenameCollection("c1", "Later"),
            decodeBookmarksIntent("RenameCollection", """{"collectionId":"c1","newName":"Later"}"""),
        )
        assertEquals(
            BookmarksIntent.AddFavoriteWorkspace("w1", "Main"),
            decodeBookmarksIntent("AddFavoriteWorkspace", """{"id":"w1","name":"Main"}"""),
        )
    }

    @Test
    fun `missing or blank fields drop the intent`() {
        assertNull(decodeBookmarksIntent("ToggleCollectionExpanded", ""))
        assertNull(decodeBookmarksIntent("DeleteCollection", "   "))
        assertNull(decodeBookmarksIntent("OpenBookmark", """{"collectionId":"c1"}"""))
        assertNull(decodeBookmarksIntent("RenameCollection", """{"collectionId":"c1","newName":""}"""))
        // A JSON object is never read as an id: `{}` must not become a collection named "{}".
        assertNull(decodeBookmarksIntent("DeleteCollection", "{}"))
        assertNull(decodeBookmarksIntent("NoSuchIntent", "x"))
    }

    // endregion
}
