package ai.rever.boss.plugin.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PluginStateMergePatchTest {
    private fun patch(previous: String, current: String): String? =
        PluginStateMergePatch.create(
            Json.parseToJsonElement(previous) as JsonObject,
            Json.parseToJsonElement(current) as JsonObject,
        )?.toString(Charsets.UTF_8)

    @Test
    fun `patch represents nested edits deletions and array replacement`() {
        val actual = assertNotNull(
            patch(
                """{"user":{"name":"A","age":20},"items":[1,2],"old":true}""",
                """{"user":{"name":"B","age":20},"items":[3]}""",
            ),
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"old":null,"user":{"name":"B"},"items":[3]}""",
            ),
            Json.parseToJsonElement(actual),
        )
    }

    @Test
    fun `adding or replacing explicit null requires full state`() {
        assertNull(patch("""{}""", """{"value":null}"""))
        assertNull(patch("""{"value":1}""", """{"value":null}"""))
        assertNull(patch("""{}""", """{"nested":{"value":null}}"""))
    }

    @Test
    fun `unchanged null remains present without being patched`() {
        assertEquals(
            """{"count":2}""",
            patch(
                """{"value":null,"count":1}""",
                """{"value":null,"count":2}""",
            ),
        )
    }

    @Test
    fun `unsupported input and excessive depth require full state`() {
        for (input in listOf("not json", "[]", "42", "null")) {
            assertNull(
                PluginStateMergePatch.parseObject(
                    input.toByteArray(Charsets.UTF_8),
                ),
            )
        }

        assertNull(
            PluginStateMergePatch.parseObject(
                byteArrayOf(0xC3.toByte(), 0x28),
            ),
        )

        val deeplyNested = """{"a":""".repeat(65) + "0" + "}".repeat(65)
        assertNull(
            PluginStateMergePatch.parseObject(
                deeplyNested.toByteArray(Charsets.UTF_8),
            ),
        )
    }
}