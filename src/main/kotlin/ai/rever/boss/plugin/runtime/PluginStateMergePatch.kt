package ai.rever.boss.plugin.runtime

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Generates JSON Merge Patches for object-shaped plugin states.
 *
 * Kotlin null means that a full snapshot is required.
 * JsonNull inside a patch means that an object member is deleted.
 */
internal object PluginStateMergePatch {
    private const val MAX_DEPTH = 64

    fun parseObject(bytes: ByteArray): JsonObject? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }

        // Check before parsing so deeply nested input never reaches
        // the recursive JSON parser or patch generator.
        if (!withinDepthLimit(text)) return null

        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun create(
        previous: JsonObject,
        current: JsonObject,
    ): ByteArray? =
        difference(previous, current)
            ?.toString()
            ?.toByteArray(Charsets.UTF_8)

    private fun difference(
        previous: JsonElement?,
        current: JsonElement,
    ): JsonElement? {
        // Arrays and primitives are replaced as complete values.
        if (current !is JsonObject) return current

        val previousObject = previous as? JsonObject
        val patch = linkedMapOf<String, JsonElement>()

        if (previousObject != null) {
            for (key in previousObject.keys) {
                if (key !in current) {
                    patch[key] = JsonNull
                }
            }
        }

        for ((key, value) in current) {
            val oldValue = previousObject?.get(key)

            // This also preserves an existing explicit null by omitting it.
            if (oldValue != null && oldValue == value) continue

            // A merge patch cannot add or replace an object member with
            // explicit null: that operation would delete the member.
            if (value == JsonNull) return null

            patch[key] = difference(oldValue, value) ?: return null
        }

        return JsonObject(patch)
    }

    private fun withinDepthLimit(text: String): Boolean {
        var depth = 0
        var insideString = false
        var escaped = false

        for (character in text) {
            if (insideString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> insideString = false
                }
            } else {
                when (character) {
                    '"' -> insideString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > MAX_DEPTH) return false
                    }
                    '}', ']' -> {
                        depth -= 1
                        if (depth < 0) return false
                    }
                }
            }
        }

        return depth == 0 && !insideString
    }
}