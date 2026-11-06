package dev.gaiusred.tuiosmobile.sync

import dev.gaiusred.tuiosmobile.protocol.TranscriptEntry
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.serialization.json.*

/** Copy display fields explicitly; never retain arbitrary JSON from a transcript. */
internal fun TranscriptEntry.cached(): CachedContent.Transcript = CachedContent.Transcript(
    role, kind, text.ifEmpty { if (kind in knownKinds) "" else "Unsupported entry: $kind" }, truncated,
    raw.text("tool"), raw.text("target"), raw.text("status"), raw.text("tool_id"), raw.text("plan"),
    raw.array("todos").map { CachedTodo(it.text("text") ?: "", it.text("status") ?: "") },
    (raw["diff"] as? JsonObject)?.let { diff ->
        CachedDiff(diff.text("file") ?: "", diff.number("added"), diff.number("removed"),
            diff.array("hunks").map { hunk ->
                CachedHunk(hunk.number("old_start"), hunk.number("new_start"),
                    hunk.array("lines").map { CachedDiffLine(it.text("op") ?: "", it.text("text") ?: "") })
            }, diff.flag("truncated"), diff.flag("plain"), diff.flag("whole_replace"))
    })

private val knownKinds = setOf("text", "thinking", "tool_call", "tool_result", "plan", "todos", "error")
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.array(key: String): List<JsonObject> = (get(key) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
