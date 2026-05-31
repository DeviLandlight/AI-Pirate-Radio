package com.aipirateradio.app.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiRadioClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val textModel: String = "gpt-4o-mini",
    private val ttsModel: String = "gpt-4o-mini-tts",
    private val ttsVoice: String = "coral",
    private val generateSpeech: Boolean = true
) : SongPicker, SegueWriter {
    override suspend fun pickSong(request: SongPickRequest): SongPickResult? {
        val responseText = createTextResponse(
            instructions = OpenAiPrompts.songPickerSystemPrompt(),
            input = OpenAiPrompts.songPickerUserPrompt(request.previousSongs, request.candidates),
            schemaName = "song_pick",
            schema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", JSONObject().put("candidateId", JSONObject().put("type", "string")).put("reason", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("candidateId").put("reason"))
        ) ?: return null
        val json = JSONObject(responseText)
        val song = request.candidates.firstOrNull { it.song.id == json.optString("candidateId") }?.song ?: return null
        return SongPickResult(song, json.optString("reason"))
    }

    override suspend fun writeSegue(request: SegueRequest): SegueResult? {
        val responseText = createTextResponse(
            instructions = "You are a warm, dryly funny late-night radio host. Return only valid JSON.",
            input = OpenAiPrompts.seguePrompt(request),
            schemaName = "song_segue",
            schema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "string")).put("segueText", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("fact").put("segueText"))
        ) ?: return null
        val json = JSONObject(responseText)
        val text = json.optString("segueText")
        val audio = if (generateSpeech) createSpeech(text) else null
        return SegueResult(json.optString("fact"), text, audio)
    }

    private suspend fun createTextResponse(instructions: String, input: String, schemaName: String, schema: JSONObject): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", textModel)
            .put("instructions", instructions)
            .put("input", input)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", schemaName).put("strict", true).put("schema", schema)))
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${apiKeyProvider()}")
            .header("Content-Type", JSON_CONTENT_TYPE)
            .post(body.toString().toRequestBody(JSON))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            extractResponseText(JSONObject(response.body?.string().orEmpty()))
        }
    }

    private suspend fun createSpeech(text: String): ByteArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val body = JSONObject().put("model", ttsModel).put("voice", ttsVoice).put("input", text).put("instructions", "Sound like a warm, understated radio host.")
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer ${apiKeyProvider()}")
            .header("Content-Type", JSON_CONTENT_TYPE)
            .post(body.toString().toRequestBody(JSON))
            .build()
        httpClient.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
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

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json"
        val JSON = JSON_CONTENT_TYPE.toMediaType()
    }
}
