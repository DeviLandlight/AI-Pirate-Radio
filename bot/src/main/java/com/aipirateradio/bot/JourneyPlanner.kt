package com.aipirateradio.bot

import com.aipirateradio.app.station.JourneyBeat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class JourneyPlanner(
    private val apiKey: String,
    private val model: String
) {
    private val httpClient = HttpClient.newHttpClient()

    fun createJourney(vibe: String, seedArtists: List<String>, count: Int): List<JourneyBeat> {
        if (apiKey.isBlank()) return fallbackJourney(count)
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject().put(
                    "beats",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", count)
                        .put("maxItems", count)
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "object")
                                .put("additionalProperties", false)
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put("number", JSONObject().put("type", "integer"))
                                        .put("word", JSONObject().put("type", "string"))
                                        .put("description", JSONObject().put("type", "string"))
                                        .put("desiredEnergy", JSONObject().put("type", "string"))
                                        .put("desiredMood", JSONObject().put("type", "string"))
                                )
                                .put("required", JSONArray().put("number").put("word").put("description").put("desiredEnergy").put("desiredMood"))
                        )
                )
            )
            .put("required", JSONArray().put("beats"))
        val input = JSONObject()
            .put("vibe", vibe)
            .put("seedArtists", JSONArray(seedArtists))
            .put("beatCount", count)
            .toString()
        val response = createTextResponse(
            instructions = """
                Create a radio journey arc for a playlist. Return exactly beatCount beats.
                Each beat should have one evocative label word and a one-sentence chapter description.
                The description is the important part: write it as a story stage, like "The hero realizes things cannot continue as they are."
                Make the arc fit the vibe and seed artists, but keep it flexible enough for seemingly unrelated songs.
                Favor theatrical, emotional, funny, strange, concept-album-friendly language.
                Avoid generic labels like intro, middle, ending, climax unless there is a more vivid word.
                Do not name specific songs. Do not make the description longer than one sentence.
                Return JSON only.
            """.trimIndent(),
            input = input,
            schemaName = "radio_journey_arc",
            schema = schema
        ) ?: return fallbackJourney(count)
        return runCatching {
            val beats = JSONObject(response).optJSONArray("beats") ?: return@runCatching fallbackJourney(count)
            buildList {
                for (i in 0 until beats.length()) {
                    val beat = beats.optJSONObject(i) ?: continue
                    add(
                        JourneyBeat(
                            number = beat.optInt("number", i + 1).coerceAtLeast(1),
                            word = beat.optString("word").trim().ifBlank { fallbackJourney(count)[i].word },
                            description = beat.optString("description").trim().ifBlank { fallbackJourney(count)[i].description },
                            desiredEnergy = beat.optString("desiredEnergy").trim(),
                            desiredMood = beat.optString("desiredMood").trim()
                        )
                    )
                }
            }.take(count).mapIndexed { index, beat -> beat.copy(number = index + 1) }
        }.getOrNull()?.takeIf { it.size == count } ?: fallbackJourney(count)
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

fun fallbackJourney(count: Int): List<JourneyBeat> {
    val beats = listOf(
        JourneyBeat(1, "Curtain", "The world opens with theatrical confidence and dares the listener to step inside.", "medium", "inviting"),
        JourneyBeat(2, "Spark", "A strange idea catches fire before anyone fully understands what it means.", "medium-high", "curious"),
        JourneyBeat(3, "Boast", "The hero gets louder than their plan deserves and mistakes momentum for destiny.", "high", "playful"),
        JourneyBeat(4, "Misfire", "The first plan goes sideways, but the mistake is entertaining enough to become the new plan.", "medium", "comic"),
        JourneyBeat(5, "Reckoning", "The joke reveals a real emotional edge and the room gets a little quieter.", "medium", "tense"),
        JourneyBeat(6, "Detour", "The path bends into an unexpected room where the rules no longer quite match.", "medium", "strange"),
        JourneyBeat(7, "Defiance", "The cast plants its feet and decides the absurd thing is worth defending.", "high", "resolved"),
        JourneyBeat(8, "Descent", "The lights dim and the story drops into a shadow it cannot simply laugh away.", "low-medium", "shadowed"),
        JourneyBeat(9, "Revelation", "A hidden meaning or bigger feeling breaks through the noise.", "medium-high", "wonder"),
        JourneyBeat(10, "Rally", "Everyone comes back together with enough momentum to make the impossible look briefly organized.", "high", "communal"),
        JourneyBeat(11, "Afterglow", "The chaos settles into warmth, reflection, and the cost of getting this far.", "medium", "bittersweet"),
        JourneyBeat(12, "Encore", "One last flourish sends the whole strange procession out under brighter lights.", "high", "satisfied")
    )
    return List(count) { index -> beats.getOrElse(index) { JourneyBeat(index + 1, "Encore", "A final flourish extends the journey.", "high", "satisfied") }.copy(number = index + 1) }
}
