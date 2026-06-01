package com.aipirateradio.bot

import com.aipirateradio.app.openai.FallbackSegueWriter
import com.aipirateradio.app.openai.FallbackSongPicker
import com.aipirateradio.app.openai.LocalSegueWriter
import com.aipirateradio.app.openai.LocalSongPicker
import com.aipirateradio.app.openai.OpenAiRadioClient
import com.aipirateradio.app.recommendations.FavoriteArtistSeed
import com.aipirateradio.app.recommendations.LastFmClient
import com.aipirateradio.app.recommendations.RecommendationRequest
import com.aipirateradio.app.recommendations.StaticSeedRecommender
import com.aipirateradio.app.station.PlayRecord
import com.aipirateradio.app.station.PreparedShow
import com.aipirateradio.app.station.RadioVibes
import com.aipirateradio.app.station.SampleCatalog
import com.aipirateradio.app.station.SegueState
import com.aipirateradio.app.station.ShowPreparer
import com.aipirateradio.app.station.StationManager
import com.aipirateradio.app.station.StationRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory
import moe.kyokobot.libdave.NativeDaveFactory
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.GatewayIntent
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant

fun main() {
    val config = BotConfig.fromEnvironment()
    val bot = PirateRadioDiscordBot(config)
    val builder = JDABuilder.createDefault(config.discordToken)
        .enableIntents(GatewayIntent.GUILD_VOICE_STATES)
        .setAudioModuleConfig(
            AudioModuleConfig()
                .withDaveSessionFactory(LDJDADaveSessionFactory(NativeDaveFactory()))
        )
        .addEventListeners(bot)

    val jda = builder.build()
    jda.awaitReady()
    jda.updateCommands().queue()
    jda.guilds.forEach { guild ->
        guild.updateCommands().addCommands(radioCommands()).queue()
    }
    println("AI Pirate Radio bot is online.")
}

private fun radioCommands(): List<CommandData> {
    return listOf(
        Commands.slash("join", "Join your current voice channel."),
        Commands.slash("prepare", "Prepare a radio show.")
            .addOption(OptionType.STRING, "vibe", "Use `/vibes` for built-ins, or enter comma-separated artists.", false)
            .addOption(OptionType.INTEGER, "songs", "Number of songs to prepare.", false),
        Commands.slash("vibes", "List built-in radio vibes you can use with `/prepare`."),
        Commands.slash("play", "Play the prepared radio show in voice."),
        Commands.slash("queue", "Show the prepared queue."),
        Commands.slash("refresh", "Match the prepared show against newly downloaded local files."),
        Commands.slash("download-missing", "Use yt-dlp to download missing prepared tracks into your music folder.")
            .addOption(OptionType.INTEGER, "limit", "Maximum tracks to download this run.", false),
        Commands.slash("clear-history", "Clear saved song/artist history for your current voice channel."),
        Commands.slash("test-dj", "Speak a short DJ test line in your current voice channel."),
        Commands.slash("voices", "List installed Windows voices for DJ speech."),
        Commands.slash("status", "Check bot setup for voice playback."),
        Commands.slash("stop", "Stop playback and leave voice.")
    )
}

data class BotConfig(
    val discordToken: String,
    val musicLibraryPath: Path?,
    val dataPath: Path,
    val historyLimit: Int,
    val ytDlpPath: String,
    val djVoiceName: String?,
    val djVoiceRate: Int,
    val djVoiceVolume: Int,
    val djTtsProvider: String,
    val openAiTtsModel: String,
    val openAiTtsVoice: String,
    val lastFmApiKey: String,
    val openAiApiKey: String,
    val openAiTextModel: String,
) {
    companion object {
        fun fromEnvironment(): BotConfig {
            val token = env("DISCORD_TOKEN")
                ?: error("DISCORD_TOKEN is required.")
            return BotConfig(
                discordToken = token,
                musicLibraryPath = env("MUSIC_LIBRARY_PATH")?.let { Path.of(it) },
                dataPath = env("BOT_DATA_PATH")?.let { Path.of(it) } ?: defaultBotDataPath(),
                historyLimit = env("BOT_HISTORY_LIMIT")?.toIntOrNull()?.coerceIn(0, 2_000) ?: 240,
                ytDlpPath = env("YTDLP_PATH") ?: "yt-dlp",
                djVoiceName = env("DJ_VOICE"),
                djVoiceRate = env("DJ_VOICE_RATE")?.toIntOrNull()?.coerceIn(-10, 10) ?: 1,
                djVoiceVolume = env("DJ_VOICE_VOLUME")?.toIntOrNull()?.coerceIn(0, 100) ?: 100,
                djTtsProvider = env("DJ_TTS_PROVIDER") ?: "windows",
                openAiTtsModel = env("OPENAI_TTS_MODEL") ?: "gpt-4o-mini-tts",
                openAiTtsVoice = env("OPENAI_TTS_VOICE") ?: "coral",
                lastFmApiKey = env("LASTFM_API_KEY").orEmpty(),
                openAiApiKey = env("OPENAI_API_KEY").orEmpty(),
                openAiTextModel = env("OPENAI_TEXT_MODEL") ?: "gpt-4o-mini"
            )
        }

        private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }
    }
}

class PirateRadioDiscordBot(
    private val config: BotConfig
) : ListenerAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = BotStore(config.dataPath)
    private val sessions = mutableMapOf<RadioSessionKey, GuildRadioSession>()

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        when (event.name) {
            "join" -> join(event)
            "prepare" -> prepare(event)
            "vibes" -> vibes(event)
            "play" -> play(event)
            "queue" -> queue(event)
            "refresh" -> refresh(event)
            "download-missing" -> downloadMissing(event)
            "clear-history" -> clearHistory(event)
            "test-dj" -> testDj(event)
            "voices" -> voices(event)
            "status" -> status(event)
            "stop" -> stop(event)
        }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        event.guild.updateCommands().addCommands(radioCommands()).queue()
    }

    private fun join(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/join`.").setEphemeral(true).queue()
        val activeSession = activeSessionFor(guild.idLong)
        if (activeSession != null && activeSession.voiceChannelId != voiceChannel.idLong) {
            return event.reply("The radio is already playing in another voice channel. That channel's DJ can `/stop` to free the bot.").setEphemeral(true).queue()
        }
        guild.audioManager.sendingHandler = sessionFor(guild.idLong, voiceChannel.idLong).sendHandler
        guild.audioManager.openAudioConnection(voiceChannel)
        event.reply("Tuned in to ${voiceChannel.name}.").queue()
    }

    private fun vibes(event: SlashCommandInteractionEvent) {
        val body = RadioVibes.all.joinToString("\n\n") { vibe ->
            val seeds = vibe.artists.take(4).joinToString(", ")
            "**${vibe.name}** (`${vibe.id}`)\n${vibe.description}\nSeeds: $seeds"
        }
        event.reply("Built-in vibes:\n\n$body\n\nYou can also use `/prepare vibe:\"Artist, Artist, Artist\"`.").setEphemeral(true).queue()
    }

    private fun prepare(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/prepare`.").setEphemeral(true).queue()
        event.deferReply().queue()
        scope.launch {
            val count = event.getOption("songs")?.asLong?.toInt()?.coerceIn(1, 24) ?: 12
            val vibeText = event.getOption("vibe")?.asString.orEmpty()
            val session = sessionFor(guild.idLong, voiceChannel.idLong)
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val localSongs = library.songs()
            val recommender = if (config.lastFmApiKey.isNotBlank()) {
                LastFmClient(apiKeyProvider = { config.lastFmApiKey })
            } else if (localSongs.size >= 2) {
                DesktopMusicRecommender(library)
            } else {
                StaticSeedRecommender(SampleCatalog.songs)
            }
            val localPicker = LocalSongPicker()
            val localSegue = LocalSegueWriter()
            val openAiClient = config.openAiApiKey.takeIf { it.isNotBlank() }?.let {
                OpenAiRadioClient(
                    apiKeyProvider = { it },
                    textModel = config.openAiTextModel,
                    generateSpeech = false
                )
            }
            val artists = artistsFor(vibeText)
            val preparer = ShowPreparer(
                stationManager = StationManager(StationRules(candidateCount = 12)),
                musicRecommender = recommender,
                songPicker = openAiClient?.let { FallbackSongPicker(it, localPicker) } ?: localPicker,
                segueWriter = openAiClient?.let { FallbackSegueWriter(it, localSegue) } ?: localSegue,
                trackResolver = DesktopTrackResolver(library, allowUnmatchedPlannedSongs = true)
            )
            val show = runCatching {
                preparer.prepareShow(
                    recommendationRequest = RecommendationRequest(
                        favoriteArtists = artists.map { FavoriteArtistSeed(it) },
                        includeObscureTracks = true,
                        includeBSides = false,
                        maxArtistsPerSeed = 3,
                        maxTracksPerArtist = 2
                    ),
                    startingHistory = session.history,
                    startingSegueState = SegueState(),
                    targetSongCount = count
                )
            }.getOrElse {
                event.hook.editOriginal("Show setup failed: ${it.readableMessage()}.").queue()
                return@launch
            }
            session.preparedShow = show
            appendPreparedShowToHistory(session, show)
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            event.hook.editOriginal(show.summary("Prepared") + show.availabilityNote(localSongs.size)).queue()
        }
    }

    private fun play(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/play`.").setEphemeral(true).queue()
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        val show = session.preparedShow
            ?: return event.reply("Prepare a show first with `/prepare`.").setEphemeral(true).queue()
        val activeSession = activeSessionFor(guild.idLong)
        if (activeSession != null && activeSession.voiceChannelId != voiceChannel.idLong) {
            return event.reply("The radio is already playing in another voice channel. That channel's DJ can `/stop` to free the bot.").setEphemeral(true).queue()
        }
        if (session.djUserId != null && session.djUserId != event.user.idLong) {
            return event.reply("This channel already has a DJ: <@${session.djUserId}>. They can `/stop` to release the deck.").setEphemeral(true).queue()
        }
        if (!session.player.isFfmpegAvailable()) {
            return event.reply("I cannot play audio yet because `ffmpeg` is not available on this computer's PATH. Install ffmpeg, restart PowerShell, then start the bot again.").setEphemeral(true).queue()
        }
        if (show.tracks.none { it.song.localPathOrNull() != null }) {
            return event.reply("This show has no local desktop audio files attached. Set `MUSIC_LIBRARY_PATH`, restart the bot, then run `/prepare` again.").setEphemeral(true).queue()
        }
        guild.audioManager.sendingHandler = session.sendHandler
        guild.audioManager.openAudioConnection(voiceChannel)
        session.djUserId = event.user.idLong
        session.voiceChannelId = voiceChannel.idLong
        session.isPlaying = true
        event.reply("Starting the show in ${voiceChannel.name}.").queue()
        scope.launch {
            session.player.resetForPlayback()
            val speech = speechSynthesizer()
            show.tracks.forEachIndexed { index, track ->
                if (session.player.isStopped()) return@launch
                track.segueText?.takeIf { it.isNotBlank() }?.let {
                    event.channel.sendMessage("DJ: $it").queue()
                    val speechFile = speech(it)
                    if (speechFile != null) {
                        val spoke = session.player.playFile(speechFile.toString())
                        if (!spoke) event.channel.sendMessage("DJ voice could not play. Check `/status` for ffmpeg.").queue()
                        runCatching { Files.deleteIfExists(speechFile) }
                    } else {
                        event.channel.sendMessage("DJ voice could not be generated on this computer.").queue()
                    }
                }
                event.channel.sendMessage("Now playing ${index + 1}/${show.tracks.size}: ${track.song.artist} - ${track.song.title}").queue()
                val played = session.player.play(track.song)
                if (played) {
                    appendHistory(
                        session,
                        listOf(
                            PlayRecord(
                                songId = track.song.id,
                                title = track.song.title,
                                artist = track.song.artist,
                                album = track.song.album,
                                genreTags = track.song.genreTags,
                                startedAt = Instant.now(),
                                hadSegue = !track.segueText.isNullOrBlank()
                            )
                        )
                    )
                    store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
                } else {
                    event.channel.sendMessage("Skipped missing or unsupported file: ${track.song.artist} - ${track.song.title}").queue()
                }
            }
            session.isPlaying = false
            session.djUserId = null
            event.channel.sendMessage("Show complete.").queue()
        }
    }

    private fun status(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
        val session = voiceChannel?.let { sessionFor(guild.idLong, it.idLong) }
        val musicPath = config.musicLibraryPath?.toString() ?: "not set"
        val dataPath = config.dataPath.toString()
        val localSongs = DesktopMusicLibrary(config.musicLibraryPath).songs().size
        val ffmpeg = if ((session ?: sessionFor(guild.idLong, 0L)).player.isFfmpegAvailable()) "available" else "missing"
        val voice = voiceChannel?.name ?: "you are not in voice"
        val dj = session?.djUserId?.let { "<@$it>" } ?: "none"
        event.reply(
            """
            Music path: `$musicPath`
            Bot data path: `$dataPath`
            yt-dlp: `${config.ytDlpPath}`
            DJ TTS provider: `${config.djTtsProvider}`
            Effective DJ TTS: `${effectiveTtsProvider()}`
            OpenAI key visible: `${config.openAiApiKey.isNotBlank()}`
            DJ voice: `${if (config.djTtsProvider.equals("openai", ignoreCase = true)) config.openAiTtsVoice else config.djVoiceName ?: "Windows default"}`
            DJ voice rate/volume: `${config.djVoiceRate} / ${config.djVoiceVolume}`
            Local songs found: `$localSongs`
            ffmpeg: `$ffmpeg`
            Your voice channel: `$voice`
            Channel DJ: $dj
            Prepared show: `${session?.preparedShow?.tracks?.size ?: 0} songs`
            Saved history: `${session?.history?.size ?: 0} / ${config.historyLimit} songs`
            """.trimIndent()
        ).setEphemeral(true).queue()
    }

    private fun queue(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/queue`.").setEphemeral(true).queue()
        val show = sessionFor(guild.idLong, voiceChannel.idLong).preparedShow
            ?: return event.reply("No show prepared yet.").setEphemeral(true).queue()
        val localSongs = DesktopMusicLibrary(config.musicLibraryPath).songs().size
        event.reply(show.summary("Queue") + show.availabilityNote(localSongs)).queue()
    }

    private fun refresh(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/refresh`.").setEphemeral(true).queue()
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        val show = session.preparedShow
            ?: return event.reply("No show prepared yet.").setEphemeral(true).queue()
        event.deferReply().queue()
        scope.launch {
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val resolver = DesktopTrackResolver(library)
            val refreshed = show.copy(
                tracks = show.tracks.map { track ->
                    track.copy(song = resolver.resolve(track.song) ?: track.song)
                }
            )
            session.preparedShow = refreshed
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            event.hook.editOriginal(refreshed.summary("Refreshed") + refreshed.availabilityNote(library.songs().size)).queue()
        }
    }

    private fun downloadMissing(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/download-missing`.").setEphemeral(true).queue()
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        val show = session.preparedShow
            ?: return event.reply("No show prepared yet.").setEphemeral(true).queue()
        val musicPath = config.musicLibraryPath
            ?: return event.reply("Set `MUSIC_LIBRARY_PATH` before using `/download-missing`.").setEphemeral(true).queue()
        val missing = show.tracks
            .filter { it.song.localPathOrNull() == null }
            .take(event.getOption("limit")?.asLong?.toInt()?.coerceIn(1, 24) ?: 6)
        if (missing.isEmpty()) {
            return event.reply("No missing tracks in this channel's prepared show.").setEphemeral(true).queue()
        }

        event.deferReply().queue()
        scope.launch {
            val downloader = YtDlpDownloader(config.ytDlpPath, musicPath)
            if (!downloader.isAvailable()) {
                event.hook.editOriginal("`yt-dlp` is not available. Install it or set `YTDLP_PATH`, then restart the bot.").queue()
                return@launch
            }

            var downloaded = 0
            val failures = mutableListOf<String>()
            event.hook.editOriginal("Downloading ${missing.size} missing tracks. Only use this for content you have rights to download.").queue()
            missing.forEachIndexed { index, track ->
                val result = downloader.download(track.song)
                if (result.success) {
                    downloaded += 1
                } else {
                    failures += "${track.song.artist} - ${track.song.title}: ${result.message}"
                }
                event.hook.editOriginal("Downloaded $downloaded/${index + 1}. Failures: ${failures.size}.").queue()
            }

            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val resolver = DesktopTrackResolver(library)
            val refreshed = show.copy(
                tracks = show.tracks.map { track -> track.copy(song = resolver.resolve(track.song) ?: track.song) }
            )
            session.preparedShow = refreshed
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            val failureText = failures.take(5).joinToString("\n").takeIf { it.isNotBlank() }?.let { "\n\nFailures:\n$it" }.orEmpty()
            event.hook.editOriginal(refreshed.summary("Downloaded and refreshed") + refreshed.availabilityNote(library.songs().size) + failureText).queue()
        }
    }

    private fun clearHistory(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/clear-history`.").setEphemeral(true).queue()
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        val removed = session.history.size
        session.history.clear()
        store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
        event.reply("Cleared $removed saved history entries for ${voiceChannel.name}.").setEphemeral(true).queue()
    }

    private fun testDj(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/test-dj`.").setEphemeral(true).queue()
        val activeSession = activeSessionFor(guild.idLong)
        if (activeSession != null && activeSession.voiceChannelId != voiceChannel.idLong) {
            return event.reply("The radio is already playing in another voice channel. That channel's DJ can `/stop` to free the bot.").setEphemeral(true).queue()
        }
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        guild.audioManager.sendingHandler = session.sendHandler
        guild.audioManager.openAudioConnection(voiceChannel)
        event.deferReply().queue()
        scope.launch {
            if (!session.player.isFfmpegAvailable()) {
                event.hook.editOriginal("DJ test failed: `ffmpeg` is not available to the running bot.").queue()
                return@launch
            }
            if (config.djTtsProvider.equals("openai", ignoreCase = true) && config.openAiApiKey.isBlank()) {
                event.hook.editOriginal("DJ test is falling back to Windows because `OPENAI_API_KEY` is not visible to the running bot.").queue()
                return@launch
            }
            val speechFile = speechSynthesizer()("AI Pirate Radio DJ voice test. If you can hear this, the segue voice is working.")
            if (speechFile == null) {
                event.hook.editOriginal("DJ test failed: Windows speech could not create a voice file.").queue()
                return@launch
            }
            session.player.resetForPlayback()
            val played = session.player.playFile(speechFile.toString())
            runCatching { Files.deleteIfExists(speechFile) }
            event.hook.editOriginal(if (played) "DJ test sent to ${voiceChannel.name}." else "DJ test failed: voice file could not be streamed.").queue()
        }
    }

    private fun voices(event: SlashCommandInteractionEvent) {
        val voices = DesktopSpeechSynthesizer.listVoiceNames()
        val message = if (voices.isEmpty()) {
            "No installed Windows speech voices were found."
        } else {
            "Installed DJ voices:\n" + voices.joinToString("\n") { "- `$it`" } +
                "\n\nSet one with `DJ_VOICE`, then restart the bot."
        }
        event.reply(message).setEphemeral(true).queue()
    }

    private fun speechSynthesizer(): (String) -> Path? {
        return if (config.djTtsProvider.equals("openai", ignoreCase = true) && config.openAiApiKey.isNotBlank()) {
            val synth = OpenAiSpeechSynthesizer(
                apiKey = config.openAiApiKey,
                outputDirectory = config.dataPath.resolve("tts-cache"),
                model = config.openAiTtsModel,
                voice = config.openAiTtsVoice
            )
            synth::speakToFile
        } else {
            val synth = DesktopSpeechSynthesizer(
                outputDirectory = config.dataPath.resolve("tts-cache"),
                voiceName = config.djVoiceName,
                rate = config.djVoiceRate,
                volume = config.djVoiceVolume
            )
            synth::speakToFile
        }
    }

    private fun effectiveTtsProvider(): String {
        return if (config.djTtsProvider.equals("openai", ignoreCase = true) && config.openAiApiKey.isNotBlank()) {
            "openai"
        } else {
            "windows"
        }
    }

    private fun stop(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join the voice channel you want to stop first.").setEphemeral(true).queue()
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        if (session.djUserId != null && session.djUserId != event.user.idLong) {
            return event.reply("Only this channel's DJ, <@${session.djUserId}>, can stop this show.").setEphemeral(true).queue()
        }
        session.player.stop()
        session.isPlaying = false
        session.djUserId = null
        guild.audioManager.closeAudioConnection()
        event.reply("Radio stopped.").queue()
    }

    private fun sessionFor(guildId: Long, voiceChannelId: Long): GuildRadioSession =
        sessions.getOrPut(RadioSessionKey(guildId, voiceChannelId)) {
            val persisted = store.loadSession(RadioSessionKey(guildId, voiceChannelId))
            val handler = DiscordPcmSendHandler()
            GuildRadioSession(
                sendHandler = handler,
                player = DiscordAudioPlayer(handler),
                preparedShow = persisted.preparedShow,
                history = persisted.history.toMutableList()
            ).also { trimHistory(it) }
        }

    private fun activeSessionFor(guildId: Long): GuildRadioSession? =
        sessions.entries.firstOrNull { (key, session) -> key.guildId == guildId && session.isPlaying }?.value

    private fun appendPreparedShowToHistory(session: GuildRadioSession, show: PreparedShow) {
        val now = Instant.now()
        val records = show.tracks.mapIndexed { index, track ->
            PlayRecord(
                songId = track.song.id,
                title = track.song.title,
                artist = track.song.artist,
                album = track.song.album,
                genreTags = track.song.genreTags,
                startedAt = now.plusSeconds(index.toLong()),
                hadSegue = !track.segueText.isNullOrBlank()
            )
        }
        appendHistory(session, records)
    }

    private fun appendHistory(session: GuildRadioSession, records: List<PlayRecord>) {
        if (config.historyLimit == 0) {
            session.history.clear()
            return
        }
        session.history += records
        trimHistory(session)
    }

    private fun trimHistory(session: GuildRadioSession) {
        if (config.historyLimit == 0) {
            session.history.clear()
            return
        }
        val overflow = session.history.size - config.historyLimit
        if (overflow > 0) {
            repeat(overflow) { session.history.removeAt(0) }
        }
    }

    private fun artistsFor(vibeText: String): List<String> {
        if (vibeText.isNotBlank()) {
            val matchingVibe = RadioVibes.all.firstOrNull { it.name.equals(vibeText, ignoreCase = true) || it.id.equals(vibeText, ignoreCase = true) }
            return matchingVibe?.artists ?: vibeText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        return RadioVibes.default.artists
    }
}

data class RadioSessionKey(val guildId: Long, val voiceChannelId: Long)

data class GuildRadioSession(
    val sendHandler: DiscordPcmSendHandler,
    val player: DiscordAudioPlayer,
    var preparedShow: PreparedShow? = null,
    val history: MutableList<PlayRecord> = mutableListOf(),
    var djUserId: Long? = null,
    var voiceChannelId: Long? = null,
    var isPlaying: Boolean = false
)

private fun PreparedShow.summary(title: String): String {
    if (tracks.isEmpty()) return "$title: no songs found."
    val body = tracks.take(12).mapIndexed { index, track ->
        val prefix = if (track.song.localPathOrNull() != null) "Ready" else "Need"
        "${index + 1}. $prefix: ${track.song.artist} - ${track.song.title}"
    }.joinToString("\n")
    val suffix = if (tracks.size > 12) "\n...and ${tracks.size - 12} more." else ""
    return "$title: ${tracks.size} songs\n$body$suffix"
}

private fun PreparedShow.availabilityNote(localSongCount: Int): String {
    val ready = tracks.count { it.song.localPathOrNull() != null }
    val missing = tracks.size - ready
    return "\n\nDesktop files found: $localSongCount\nReady to play: $ready\nNeed downloading/matching: $missing"
}

private fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
