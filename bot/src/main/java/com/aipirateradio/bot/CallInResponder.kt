package com.aipirateradio.bot

import com.aipirateradio.app.station.Song
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class CallInResponder(
    private val apiKey: String,
    private val model: String
) {
    private val httpClient = HttpClient.newHttpClient()

    fun answer(question: CallInQuestion, vibe: String, nowPlaying: Song?, upNext: Song?): String {
        if (apiKey.isBlank()) return fallbackAnswer(question, vibe)
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject().put("answer", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("answer"))
        val input = JSONObject()
            .put("caller", question.displayName)
            .put("question", question.question)
            .put("currentVibe", vibe)
            .put("nowPlaying", nowPlaying?.let { JSONObject().put("artist", it.artist).put("title", it.title) })
            .put("upNext", upNext?.let { JSONObject().put("artist", it.artist).put("title", it.title) })
            .toString()
        val body = JSONObject()
            .put("model", model)
            .put(
                "instructions",
                """
                You are the Radio Skittles late-night DJ answering a listener call-in between songs.
                Keep it 1-3 sentences, warm, dryly funny, and suitable to speak aloud.
                If the question is not about music, answer through music, radio, mood, listening, or the current set.
                Do not give medical, legal, financial, or dangerous advice; gently redirect through radio/music.
                Do not be mean to the caller. Return JSON only.
                """.trimIndent()
            )
            .put("input", input)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", "call_in_answer").put("strict", true).put("schema", schema)))
            .toString()
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                if (response.statusCode() !in 200..299) return@let fallbackAnswer(question, vibe)
                val text = extractResponseText(JSONObject(response.body())) ?: return@let fallbackAnswer(question, vibe)
                JSONObject(text).optString("answer").takeIf { it.isNotBlank() } ?: fallbackAnswer(question, vibe)
            }
        }.getOrDefault(fallbackAnswer(question, vibe))
    }

    private fun fallbackAnswer(question: CallInQuestion, vibe: String): String {
        return "${question.displayName} asks: ${question.question} Around here, I would run that through the $vibe filter: listen for the part that changes the room, then trust your ears."
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
