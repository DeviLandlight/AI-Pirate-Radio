package com.aipirateradio.bot

import com.aipirateradio.app.station.Song
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class RequestSegueWriter(
    private val apiKey: String,
    private val model: String
) {
    private val httpClient = HttpClient.newHttpClient()

    fun write(
        song: Song,
        currentVibe: String,
        fitDecision: RequestFitDecision?,
        requesterName: String,
        artistRequest: String?,
        playful: Boolean
    ): String? {
        if (apiKey.isBlank()) return null
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("segueText", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray().put("segueText"))
        val input = JSONObject()
            .put("requesterName", requesterName)
            .put("currentVibe", currentVibe)
            .put("song", song.toRequestJson())
            .put("artistRequest", artistRequest ?: JSONObject.NULL)
            .put("fit", fitDecision?.fit ?: "unknown")
            .put("fitReason", fitDecision?.reason ?: "")
            .put("isStretch", fitDecision?.isStretch == true)
            .put(
                "styleHint",
                if (playful) {
                    "This request has theatrical, goofy, fantasy, pirate, or knowingly ridiculous energy. A playful wink is welcome."
                } else {
                    "Keep it warm, specific, and radio-natural."
                }
            )
            .toString()
        val response = createTextResponse(
            instructions = """
                Write a short in-character radio DJ segue for an accepted listener request.
                Mention the request naturally. If artistRequest is present, say the station chose this track for that artist request.
                Use the fit label and reason as context. If the fit is stretch, acknowledge that it nudges the set somewhere unexpected without rejecting it.
                Give the DJ a point of view: warm, dryly funny, curious about odd choices, and fond of theatrical ambition, emotional sincerity, weird left-field picks, and artists who fully commit to the bit.
                Let the DJ offer one small opinion. They can gently tease a song, admit a pick is strange, or say why a moment works.
                Avoid these repeated phrases: "bends the shape", "curveball earns its place", "left turn", "shifting gears", "changing lanes", "long drive".
                Avoid worn-out hype words: anthemic, anthem, masterpiece, iconic, essential, legendary, epic, classic, gem, banger, perfect fit, absolute must-listen.
                Do not invent facts, chart history, lyrics, meanings, or song plot details you were not given. Musical flavor and playful framing are fine.
                Keep it to 1 or 2 sentences, no more than 45 words.
                Return JSON only.
            """.trimIndent(),
            input = input,
            schemaName = "request_segue",
            schema = schema
        ) ?: return null
        return runCatching {
            JSONObject(response).optString("segueText").trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun createTextResponse(instructions: String, input: String, schemaName: String, schema: JSONObject): String? {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", schemaName).put("strict", true).put("schema", schema)))
            .toString()
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                if (response.statusCode() in 200..299) extractResponseText(JSONObject(response.body())) else null
            }
        }.getOrNull()
    }

    private fun extractResponseText(json: JSONObject): String? {
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = json.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                content.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }
}

private fun Song.toRequestJson(): JSONObject =
    JSONObject()
        .put("artist", artist)
        .put("title", title)
        .put("album", album ?: JSONObject.NULL)
        .put("genreTags", JSONArray(genreTags))
        .put("moodTags", JSONArray(moodTags))
