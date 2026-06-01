package com.aipirateradio.bot

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class OpenAiSpeechSynthesizer(
    private val apiKey: String,
    private val outputDirectory: Path,
    private val model: String = "gpt-4o-mini-tts",
    private val voice: String = "coral"
) {
    private val client = HttpClient.newHttpClient()

    fun speakToFile(text: String): Path? {
        if (apiKey.isBlank() || text.isBlank()) return null
        Files.createDirectories(outputDirectory)
        val output = outputDirectory.resolve("dj-${UUID.randomUUID()}.mp3")
        val body = JSONObject()
            .put("model", model)
            .put("voice", voice)
            .put("input", text.normalizeForSpeech())
            .put("instructions", "Sound like a warm, understated late-night radio host. Natural, conversational, not theatrical.")
            .toString()

        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/audio/speech"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = runCatching {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }.getOrNull() ?: return null

        if (response.statusCode() !in 200..299) return null
        Files.write(output, response.body())
        return output
    }
}

fun String.normalizeForSpeech(): String {
    return replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('\u2013', '-')
        .replace('\u2014', '-')
        .replace('\u2026', '.')
}
