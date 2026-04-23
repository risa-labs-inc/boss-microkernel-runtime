package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.PluginStateRequest
import ai.rever.boss.plugin.runtime.stateholders.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Integration tests for the out-of-process plugin state sync infrastructure.
 *
 * Validates the ConsoleStateHolder MVI flow, state serialization round-trips,
 * PluginStateSyncService envelope generation, intent deserialization, and
 * log filtering logic.
 */
class PluginOopIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createScope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun sampleLogs() = listOf(
        ConsoleLogEntry(timestamp = 1000L, message = "Starting server", source = ConsoleLogSource.STDOUT),
        ConsoleLogEntry(timestamp = 2000L, message = "Error: connection refused", source = ConsoleLogSource.STDERR),
        ConsoleLogEntry(timestamp = 3000L, message = "Listening on port 8080", source = ConsoleLogSource.STDOUT),
        ConsoleLogEntry(timestamp = 4000L, message = "Error: timeout", source = ConsoleLogSource.STDERR),
        ConsoleLogEntry(timestamp = 5000L, message = "Request handled successfully", source = ConsoleLogSource.STDOUT),
    )

    // ---------------------------------------------------------------
    // 1. ConsoleStateHolder basic MVI flow
    // ---------------------------------------------------------------

    @Test
    fun `initial state has empty logs and default values`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        val state = holder.currentState()
        assertEquals(emptyList(), state.logs)
        assertEquals(ConsoleLogFilter.ALL, state.filter)
        assertEquals("", state.searchQuery)
        assertTrue(state.autoScroll)

        scope.cancel()
    }

    @Test
    fun `NewLogs intent appends log entries`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        val entries = sampleLogs().take(2)
        holder.onIntent(ConsoleIntent.NewLogs(entries))

        val state = holder.currentState()
        assertEquals(2, state.logs.size)
        assertEquals("Starting server", state.logs[0].message)
        assertEquals(ConsoleLogSource.STDERR, state.logs[1].source)

        scope.cancel()
    }

    @Test
    fun `multiple NewLogs intents accumulate entries`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs().take(2)))
        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs().drop(2)))

        assertEquals(5, holder.currentState().logs.size)

        scope.cancel()
    }

    @Test
    fun `SetFilter intent updates filter`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDERR))

        val state = holder.currentState()
        assertEquals(ConsoleLogFilter.STDERR, state.filter)
        assertTrue(state.logs.all { it.source == ConsoleLogSource.STDERR })

        scope.cancel()
    }

    @Test
    fun `SetSearchQuery intent updates search query`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetSearchQuery("Error"))

        val state = holder.currentState()
        assertEquals("Error", state.searchQuery)
        assertTrue(state.logs.all { it.message.contains("Error", ignoreCase = true) })

        scope.cancel()
    }

    @Test
    fun `ToggleAutoScroll flips the autoScroll flag`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        assertTrue(holder.currentState().autoScroll)

        holder.onIntent(ConsoleIntent.ToggleAutoScroll)
        assertFalse(holder.currentState().autoScroll)

        holder.onIntent(ConsoleIntent.ToggleAutoScroll)
        assertTrue(holder.currentState().autoScroll)

        scope.cancel()
    }

    @Test
    fun `ClearLogs removes all entries`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        assertEquals(5, holder.currentState().logs.size)

        holder.onIntent(ConsoleIntent.ClearLogs)
        assertEquals(0, holder.currentState().logs.size)

        scope.cancel()
    }

    @Test
    fun `ClearLogs then NewLogs starts fresh`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.ClearLogs)
        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs().take(1)))

        val state = holder.currentState()
        assertEquals(1, state.logs.size)
        assertEquals("Starting server", state.logs[0].message)

        scope.cancel()
    }

    @Test
    fun `version increments on each intent`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        assertEquals(0, holder.version)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs().take(1)))
        assertEquals(1, holder.version)

        holder.onIntent(ConsoleIntent.ToggleAutoScroll)
        assertEquals(2, holder.version)

        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDOUT))
        assertEquals(3, holder.version)

        scope.cancel()
    }

    // ---------------------------------------------------------------
    // 2. State serialization round-trip
    // ---------------------------------------------------------------

    @Test
    fun `ConsoleState serializes and deserializes to JSON`() = runTest {
        val original = ConsoleState(
            logs = sampleLogs(),
            filter = ConsoleLogFilter.STDERR,
            searchQuery = "Error",
            autoScroll = false,
        )

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<ConsoleState>(jsonString)

        assertEquals(original, deserialized)
    }

    @Test
    fun `empty ConsoleState round-trips correctly`() = runTest {
        val original = ConsoleState()

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<ConsoleState>(jsonString)

        assertEquals(original, deserialized)
    }

    @Test
    fun `ConsoleState byte array round-trip preserves data`() = runTest {
        val original = ConsoleState(
            logs = sampleLogs().take(3),
            filter = ConsoleLogFilter.ALL,
            searchQuery = "port",
            autoScroll = true,
        )

        val bytes = json.encodeToString(original).toByteArray(Charsets.UTF_8)
        val restored = json.decodeFromString<ConsoleState>(bytes.toString(Charsets.UTF_8))

        assertEquals(original, restored)
        assertEquals(original.logs.size, restored.logs.size)
        assertEquals(original.filter, restored.filter)
        assertEquals(original.searchQuery, restored.searchQuery)
        assertEquals(original.autoScroll, restored.autoScroll)
    }

    @Test
    fun `ConsoleLogEntry preserves all fields through serialization`() = runTest {
        val entry = ConsoleLogEntry(
            timestamp = 1234567890L,
            message = "Test message with special chars: <>&\"'",
            source = ConsoleLogSource.STDERR,
        )

        val jsonString = json.encodeToString(entry)
        val deserialized = json.decodeFromString<ConsoleLogEntry>(jsonString)

        assertEquals(entry.timestamp, deserialized.timestamp)
        assertEquals(entry.message, deserialized.message)
        assertEquals(entry.source, deserialized.source)
    }

    @Test
    fun `all ConsoleLogFilter values round-trip`() = runTest {
        for (filter in ConsoleLogFilter.entries) {
            val state = ConsoleState(filter = filter)
            val jsonString = json.encodeToString(state)
            val restored = json.decodeFromString<ConsoleState>(jsonString)
            assertEquals(filter, restored.filter)
        }
    }

    // ---------------------------------------------------------------
    // 3. PluginStateSyncService state streaming
    // ---------------------------------------------------------------

    @Test
    fun `sync service getCurrentState returns correct envelope`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDOUT))

        val syncService = PluginStateSyncService(
            pluginId = "console-plugin",
            instanceId = "instance-001",
            stateHolder = holder,
            serializeState = { state -> json.encodeToString(state).toByteArray() },
            deserializeIntent = { type, payload -> deserializeConsoleIntent(type, payload) },
            stateTypeName = "ConsoleState",
            scope = scope,
        )

        val request = PluginStateRequest.newBuilder()
            .setPluginId("console-plugin")
            .setInstanceId("instance-001")
            .build()

        val envelope = syncService.getCurrentState(request)

        assertEquals("console-plugin", envelope.pluginId)
        assertEquals("instance-001", envelope.instanceId)
        assertEquals("ConsoleState", envelope.stateType)
        assertTrue(envelope.version > 0)
        assertTrue(envelope.timestamp > 0)

        // Deserialize the state bytes and verify content
        val stateFromEnvelope = json.decodeFromString<ConsoleState>(
            envelope.stateBytes.toStringUtf8()
        )
        assertEquals(ConsoleLogFilter.STDOUT, stateFromEnvelope.filter)
        // Only STDOUT logs should be in the filtered state
        assertTrue(stateFromEnvelope.logs.all { it.source == ConsoleLogSource.STDOUT })

        scope.cancel()
    }

    @Test
    fun `sync service envelope version matches state holder version`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs().take(1)))
        holder.onIntent(ConsoleIntent.ToggleAutoScroll)
        holder.onIntent(ConsoleIntent.SetSearchQuery("test"))

        val syncService = PluginStateSyncService(
            pluginId = "console-plugin",
            instanceId = "instance-002",
            stateHolder = holder,
            serializeState = { state -> json.encodeToString(state).toByteArray() },
            deserializeIntent = { type, payload -> deserializeConsoleIntent(type, payload) },
            scope = scope,
        )

        val request = PluginStateRequest.newBuilder()
            .setPluginId("console-plugin")
            .setInstanceId("instance-002")
            .build()

        val envelope = syncService.getCurrentState(request)
        assertEquals(holder.version, envelope.version)

        scope.cancel()
    }

    @Test
    fun `sync service state bytes deserialize to matching state`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        val logs = sampleLogs()
        holder.onIntent(ConsoleIntent.NewLogs(logs))

        val syncService = PluginStateSyncService(
            pluginId = "console-plugin",
            instanceId = "instance-003",
            stateHolder = holder,
            serializeState = { state -> json.encodeToString(state).toByteArray() },
            deserializeIntent = { type, payload -> deserializeConsoleIntent(type, payload) },
            scope = scope,
        )

        val request = PluginStateRequest.newBuilder()
            .setPluginId("console-plugin")
            .setInstanceId("instance-003")
            .build()

        val envelope = syncService.getCurrentState(request)
        val deserializedState = json.decodeFromString<ConsoleState>(
            envelope.stateBytes.toStringUtf8()
        )

        assertEquals(holder.currentState(), deserializedState)

        scope.cancel()
    }

    // ---------------------------------------------------------------
    // 4. Intent deserialization
    // ---------------------------------------------------------------

    @Test
    fun `SetFilter intent round-trips through deserializer`() = runTest {
        val intent = deserializeConsoleIntent(
            "SetFilter",
            json.encodeToString(ConsoleLogFilter.STDERR).toByteArray()
        )
        assertTrue(intent is ConsoleIntent.SetFilter)
        assertEquals(ConsoleLogFilter.STDERR, intent.filter)
    }

    @Test
    fun `SetSearchQuery intent round-trips through deserializer`() = runTest {
        val intent = deserializeConsoleIntent(
            "SetSearchQuery",
            "\"error message\"".toByteArray()
        )
        assertTrue(intent is ConsoleIntent.SetSearchQuery)
        assertEquals("error message", intent.query)
    }

    @Test
    fun `ToggleAutoScroll intent deserializes correctly`() = runTest {
        val intent = deserializeConsoleIntent("ToggleAutoScroll", ByteArray(0))
        assertTrue(intent is ConsoleIntent.ToggleAutoScroll)
    }

    @Test
    fun `ClearLogs intent deserializes correctly`() = runTest {
        val intent = deserializeConsoleIntent("ClearLogs", ByteArray(0))
        assertTrue(intent is ConsoleIntent.ClearLogs)
    }

    @Test
    fun `unknown intent type returns null`() = runTest {
        val intent = deserializeConsoleIntent("NonExistentIntent", ByteArray(0))
        assertEquals(null, intent)
    }

    @Test
    fun `deserialized intent applied to state holder produces correct state`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))

        // Simulate receiving a SetFilter intent from the kernel via gRPC
        val intent = deserializeConsoleIntent(
            "SetFilter",
            json.encodeToString(ConsoleLogFilter.STDERR).toByteArray()
        )!!
        holder.onIntent(intent)

        val state = holder.currentState()
        assertEquals(ConsoleLogFilter.STDERR, state.filter)
        assertEquals(2, state.logs.size)
        assertTrue(state.logs.all { it.source == ConsoleLogSource.STDERR })

        scope.cancel()
    }

    // ---------------------------------------------------------------
    // 5. Filter logic
    // ---------------------------------------------------------------

    @Test
    fun `filter ALL shows all log entries`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.ALL))

        assertEquals(5, holder.currentState().logs.size)

        scope.cancel()
    }

    @Test
    fun `filter STDOUT shows only stdout entries`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDOUT))

        val state = holder.currentState()
        assertEquals(3, state.logs.size)
        assertTrue(state.logs.all { it.source == ConsoleLogSource.STDOUT })

        scope.cancel()
    }

    @Test
    fun `filter STDERR shows only stderr entries`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDERR))

        val state = holder.currentState()
        assertEquals(2, state.logs.size)
        assertTrue(state.logs.all { it.source == ConsoleLogSource.STDERR })

        scope.cancel()
    }

    @Test
    fun `search query filters by message content case-insensitively`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetSearchQuery("error"))

        val state = holder.currentState()
        assertEquals(2, state.logs.size)
        assertTrue(state.logs.all { it.message.contains("Error", ignoreCase = true) })

        scope.cancel()
    }

    @Test
    fun `search query and filter combine correctly`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        // Set filter to STDERR first, then search within those
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDERR))
        holder.onIntent(ConsoleIntent.SetSearchQuery("timeout"))

        val state = holder.currentState()
        assertEquals(1, state.logs.size)
        assertEquals("Error: timeout", state.logs[0].message)
        assertEquals(ConsoleLogSource.STDERR, state.logs[0].source)

        scope.cancel()
    }

    @Test
    fun `clearing search query restores full filter results`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDOUT))
        holder.onIntent(ConsoleIntent.SetSearchQuery("port"))
        assertEquals(1, holder.currentState().logs.size)

        holder.onIntent(ConsoleIntent.SetSearchQuery(""))
        assertEquals(3, holder.currentState().logs.size)
        assertTrue(holder.currentState().logs.all { it.source == ConsoleLogSource.STDOUT })

        scope.cancel()
    }

    @Test
    fun `resetting filter to ALL restores all matching search results`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDERR))
        holder.onIntent(ConsoleIntent.SetSearchQuery("Error"))
        assertEquals(2, holder.currentState().logs.size)

        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.ALL))
        // "Error" appears in 2 STDERR entries only
        val state = holder.currentState()
        assertEquals(2, state.logs.size)
        assertTrue(state.logs.all { it.message.contains("Error") })

        scope.cancel()
    }

    @Test
    fun `filter is applied to newly added logs`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.SetFilter(ConsoleLogFilter.STDOUT))
        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))

        val state = holder.currentState()
        assertEquals(3, state.logs.size)
        assertTrue(state.logs.all { it.source == ConsoleLogSource.STDOUT })

        scope.cancel()
    }

    @Test
    fun `search query with no matches returns empty list`() = runTest {
        val scope = createScope()
        val holder = ConsoleStateHolder(scope)

        holder.onIntent(ConsoleIntent.NewLogs(sampleLogs()))
        holder.onIntent(ConsoleIntent.SetSearchQuery("nonexistent query xyz"))

        assertEquals(0, holder.currentState().logs.size)

        scope.cancel()
    }

    // ---------------------------------------------------------------
    // Helper: intent deserializer matching the pattern used in sync services
    // ---------------------------------------------------------------

    private fun deserializeConsoleIntent(type: String, payload: ByteArray): ConsoleIntent? {
        return when (type) {
            "SetFilter" -> {
                val filter = json.decodeFromString<ConsoleLogFilter>(payload.toString(Charsets.UTF_8))
                ConsoleIntent.SetFilter(filter)
            }
            "SetSearchQuery" -> {
                val query = json.decodeFromString<String>(payload.toString(Charsets.UTF_8))
                ConsoleIntent.SetSearchQuery(query)
            }
            "ToggleAutoScroll" -> ConsoleIntent.ToggleAutoScroll
            "ClearLogs" -> ConsoleIntent.ClearLogs
            else -> null
        }
    }
}
