package com.aipirateradio.bot

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ActivityLaunchCommandRegistrar(private val botToken: String) {
    private val httpClient = HttpClient.newHttpClient()

    fun ensureLaunchCommand(applicationId: String) {
        val body = JSONArray()
            .put(
                JSONObject()
                    .put("name", "launch")
                    .put("description", "Launch Radio Skittles")
                    .put("type", PRIMARY_ENTRY_POINT)
                    .put("handler", DISCORD_LAUNCH_ACTIVITY)
                    .put("integration_types", JSONArray().put(GUILD_INSTALL).put(USER_INSTALL))
                    .put("contexts", JSONArray().put(GUILD).put(BOT_DM).put(PRIVATE_CHANNEL))
            )
            .toString()

        val request = HttpRequest.newBuilder(URI("https://discord.com/api/v10/applications/$applicationId/commands"))
            .header("Authorization", "Bot $botToken")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()

        runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }.onSuccess { response ->
            if (response.statusCode() !in 200..299) {
                println("Could not register Activity launch command: HTTP ${response.statusCode()} ${response.body()}")
            }
        }.onFailure {
            println("Could not register Activity launch command: ${it.message}")
        }
    }

    private companion object {
        const val PRIMARY_ENTRY_POINT = 4
        const val DISCORD_LAUNCH_ACTIVITY = 2
        const val GUILD_INSTALL = 0
        const val USER_INSTALL = 1
        const val GUILD = 0
        const val BOT_DM = 1
        const val PRIVATE_CHANNEL = 2
    }
}
