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
            .put("instructions", RADIO_DJ_TTS_INSTRUCTIONS)
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

    private companion object {
        val RADIO_DJ_TTS_INSTRUCTIONS = """
            Perform as a charismatic late-night radio DJ, not an audiobook narrator.
            Sound warm, amused, present, and conversational, with a slight smile in the voice.
            Add natural pauses at sentence breaks. Give important artist and song names a little emphasis.
            Let the emotion match the copy: playful lines can be a little mischievous, sincere lines can soften, high-energy lines can lift.
            Keep it intimate and radio-real. Do not shout, overact, sing, do character voices, or sound like an advertisement.
            Avoid a flat monotone. Avoid exaggerated announcer voice.
        """.trimIndent()
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
