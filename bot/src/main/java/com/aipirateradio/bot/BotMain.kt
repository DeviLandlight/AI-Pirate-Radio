package com.aipirateradio.bot

import com.aipirateradio.app.openai.FallbackSegueWriter
import com.aipirateradio.app.openai.FallbackSongPicker
import com.aipirateradio.app.openai.LocalSegueWriter
import com.aipirateradio.app.openai.LocalSongPicker
import com.aipirateradio.app.openai.OpenAiRadioClient
import com.aipirateradio.app.openai.SegueRequest
import com.aipirateradio.app.openai.SegueType
import com.aipirateradio.app.recommendations.FavoriteArtistSeed
import com.aipirateradio.app.recommendations.LastFmClient
import com.aipirateradio.app.recommendations.MusicRecommender
import com.aipirateradio.app.recommendations.RecommendationRequest
import com.aipirateradio.app.recommendations.StaticSeedRecommender
import com.aipirateradio.app.station.ArtistGroups
import com.aipirateradio.app.station.Candidate
import com.aipirateradio.app.station.JourneyBeat
import com.aipirateradio.app.station.PlayRecord
import com.aipirateradio.app.station.PreparedShow
import com.aipirateradio.app.station.PreparedShowTrack
import com.aipirateradio.app.station.RadioVibes
import com.aipirateradio.app.station.SampleCatalog
import com.aipirateradio.app.station.SegueState
import com.aipirateradio.app.station.SeasonalMusicPolicy
import com.aipirateradio.app.station.ShowPreparer
import com.aipirateradio.app.station.Song
import com.aipirateradio.app.station.StationManager
import com.aipirateradio.app.station.StationRules
import com.aipirateradio.app.station.TrackQualityPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory
import moe.kyokobot.libdave.NativeDaveFactory
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.channel.attribute.IVoiceStatusChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.GatewayIntent
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

fun main() {
    val config = BotConfig.fromEnvironment()
    val visualizerState = RadioVisualizerState()
    val visualizerServer = VisualizerServer(
        port = config.visualizerPort,
        state = visualizerState,
        coverCacheDirectory = config.dataPath.resolve("visualizer-covers"),
        lastFmApiKey = config.lastFmApiKey
    )
    visualizerServer.start()
    val bot = PirateRadioDiscordBot(config, visualizerState)
    val builder = JDABuilder.createDefault(config.discordToken)
        .enableIntents(GatewayIntent.GUILD_VOICE_STATES)
        .setActivity(Activity.customStatus("📻 Broadcasting questionable music choices"))
        .setAudioModuleConfig(
            AudioModuleConfig()
                .withDaveSessionFactory(LDJDADaveSessionFactory(NativeDaveFactory()))
        )
        .addEventListeners(bot)

    val jda = builder.build()
    jda.awaitReady()
    jda.presence.activity = Activity.customStatus("📻 Broadcasting questionable music choices")
    ActivityLaunchCommandRegistrar(config.discordToken).ensureLaunchCommand(jda.selfUser.id)
    jda.guilds.forEach { guild ->
        guild.updateCommands().addCommands(radioCommands()).queue()
    }
    println("AI Pirate Radio bot is online.")
    if (config.visualizerPort > 0) println("Visualizer is online at http://localhost:${config.visualizerPort}")
}

private fun radioCommands(): List<CommandData> {
    return listOf(
        Commands.slash("join", "Join your current voice channel."),
        Commands.slash("prepare", "Prepare a radio show.")
            .addOption(OptionType.STRING, "vibe", "Use `/vibes` for built-ins, or enter comma-separated artists.", false)
            .addOption(OptionType.INTEGER, "songs", "Number of songs to prepare.", false),
        Commands.slash("prepare-next", "Append another radio block after the current queue.")
            .addOption(OptionType.STRING, "vibe", "Use `/vibes` for built-ins, or enter comma-separated artists.", false)
            .addOption(OptionType.INTEGER, "songs", "Number of songs to append.", false),
        Commands.slash("prepare-journey", "Prepare a chapter-based radio journey.")
            .addOption(OptionType.STRING, "vibe", "Use `/vibes` for built-ins, or enter comma-separated artists.", false)
            .addOption(OptionType.INTEGER, "songs", "Number of journey chapters/songs.", false),
        Commands.slash("prepare-album", "Prepare a full album from MusicBrainz track order.")
            .addOption(OptionType.STRING, "artist", "Album artist, like Gloryhammer.", true)
            .addOption(OptionType.STRING, "album", "Optional album title. If omitted, MusicBrainz picks a likely album.", false),
        Commands.slash("prepare-local", "Prepare a radio show using only files already in your music folder.")
            .addOption(OptionType.INTEGER, "songs", "Number of songs to prepare.", false),
        Commands.slash("vibes", "List built-in radio vibes you can use with `/prepare`."),
        Commands.slash("play", "Play the prepared radio show in voice."),
        Commands.slash("queue", "Show the prepared queue."),
        Commands.slash("request", "Request a song for the current prepared show.")
            .addOption(OptionType.STRING, "song", "Example: Gin Blossoms - Hey Jealousy, Hey Jealousy by Gin Blossoms, or Foo Fighters.", true),
        Commands.slash("ask", "Ask the DJ a call-in question for between songs.")
            .addOption(OptionType.STRING, "question", "Ask something music-related, or let the DJ bend it back toward the set.", true),
        Commands.slash("refresh", "Match the prepared show against newly downloaded local files."),
        Commands.slash("download-missing", "Use yt-dlp to download missing prepared tracks into your music folder.")
            .addOption(OptionType.INTEGER, "limit", "Maximum tracks to download this run.", false),
        Commands.slash("clear-history", "Clear saved song/artist history for your current voice channel."),
        Commands.slash("test-dj", "Speak a short DJ test line in your current voice channel."),
        Commands.slash("voices", "List installed Windows voices for DJ speech."),
        Commands.slash("status", "Check bot setup for voice playback."),
        Commands.slash("pause", "Pause this channel's show so `/play` can resume later."),
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
    val visualizerPort: Int,
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
                openAiTextModel = env("OPENAI_TEXT_MODEL") ?: "gpt-4o-mini",
                visualizerPort = env("VISUALIZER_PORT")?.toIntOrNull()?.coerceIn(0, 65_535) ?: 8787
            )
        }

        private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }
    }
}

class PirateRadioDiscordBot(
    private val config: BotConfig,
    private val visualizerState: RadioVisualizerState = RadioVisualizerState()
) : ListenerAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = BotStore(config.dataPath)
    private val sessions = mutableMapOf<RadioSessionKey, GuildRadioSession>()

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        when (event.name) {
            "join" -> join(event)
            "prepare" -> prepare(event)
            "prepare-next" -> prepareNext(event)
            "prepare-journey" -> prepareJourney(event)
            "prepare-album" -> prepareAlbum(event)
            "prepare-local" -> prepareLocal(event)
            "vibes" -> vibes(event)
            "play" -> play(event)
            "queue" -> queue(event)
            "request" -> requestSong(event)
            "ask" -> askDj(event)
            "refresh" -> refresh(event)
            "download-missing" -> downloadMissing(event)
            "clear-history" -> clearHistory(event)
            "test-dj" -> testDj(event)
            "voices" -> voices(event)
            "status" -> status(event)
            "pause" -> pause(event)
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
            val vibeLabel = vibeLabelFor(vibeText)
            visualizerState.preparing(vibeLabel)
            val session = sessionFor(guild.idLong, voiceChannel.idLong)
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val localSongs = library.songs()
            val lastFmClient = config.lastFmApiKey.takeIf { it.isNotBlank() }?.let {
                LastFmClient(apiKeyProvider = { it })
            }
            val recommender = if (lastFmClient != null) {
                lastFmClient
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
                trackResolver = DesktopTrackResolver(library, allowUnmatchedPlannedSongs = true),
                trackFactFinder = lastFmClient ?: com.aipirateradio.app.station.EmptyTrackFactFinder
            )
            val show = runCatching {
                prepareBatchShow(
                    vibeLabel = vibeLabel,
                    seedArtists = artists,
                    musicRecommender = recommender,
                    library = library,
                    history = session.history,
                    targetSongCount = count
                ) ?: preparer.prepareShow(
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
            if (show.tracks.size < count) {
                event.hook.editOriginal("I only found ${show.tracks.size}/$count songs for `$vibeLabel`. Try broader seeds, clear history, or use `/prepare-local` for local testing.").queue()
                return@launch
            }
            session.preparedShow = show
            session.vibeLabel = vibeLabel
            session.queueVibes = MutableList(show.tracks.size) { vibeLabel }
            session.nextTrackIndex = 0
            session.journeyLockedUntilIndex = 0
            appendPreparedShowToHistory(session, show)
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            visualizerState.prepared(show, vibeLabel)
            event.hook.editOriginal(show.summary("Prepared") + show.availabilityNote(localSongs.size)).queue()
        }
    }

    private fun prepareNext(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/prepare-next`.").setEphemeral(true).queue()
        event.deferReply().queue()
        scope.launch {
            val count = event.getOption("songs")?.asLong?.toInt()?.coerceIn(1, 24) ?: 12
            val vibeText = event.getOption("vibe")?.asString.orEmpty()
            val vibeLabel = vibeLabelFor(vibeText)
            val session = sessionFor(guild.idLong, voiceChannel.idLong)
            val existingShow = session.preparedShow
            if (existingShow == null || existingShow.tracks.isEmpty() || session.nextTrackIndex >= existingShow.tracks.size) {
                event.hook.editOriginal("No active queue to append to. Use `/prepare` to start a new show.").queue()
                return@launch
            }
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val localSongs = library.songs()
            val lastFmClient = config.lastFmApiKey.takeIf { it.isNotBlank() }?.let {
                LastFmClient(apiKeyProvider = { it })
            }
            val recommender = if (lastFmClient != null) {
                lastFmClient
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
            val preparer = ShowPreparer(
                stationManager = StationManager(StationRules(candidateCount = 12)),
                musicRecommender = recommender,
                songPicker = openAiClient?.let { FallbackSongPicker(it, localPicker) } ?: localPicker,
                segueWriter = openAiClient?.let { FallbackSegueWriter(it, localSegue) } ?: localSegue,
                trackResolver = DesktopTrackResolver(library, allowUnmatchedPlannedSongs = true),
                trackFactFinder = lastFmClient ?: com.aipirateradio.app.station.EmptyTrackFactFinder
            )
            val historyForAppend = session.history + existingShow.tracks.map { track ->
                PlayRecord(
                    songId = track.song.id,
                    title = track.song.title,
                    artist = track.song.artist,
                    album = track.song.album,
                    genreTags = track.song.genreTags,
                    startedAt = Instant.now(),
                    hadSegue = !track.segueText.isNullOrBlank()
                )
            }
            val nextBlock = runCatching {
                prepareBatchShow(
                    vibeLabel = vibeLabel,
                    seedArtists = artistsFor(vibeText),
                    musicRecommender = recommender,
                    library = library,
                    history = historyForAppend,
                    targetSongCount = count
                ) ?: preparer.prepareShow(
                    recommendationRequest = RecommendationRequest(
                        favoriteArtists = artistsFor(vibeText).map { FavoriteArtistSeed(it) },
                        includeObscureTracks = true,
                        includeBSides = false,
                        maxArtistsPerSeed = 3,
                        maxTracksPerArtist = 2
                    ),
                    startingHistory = historyForAppend,
                    startingSegueState = SegueState(),
                    targetSongCount = count
                )
            }.getOrElse {
                event.hook.editOriginal("Next block setup failed: ${it.readableMessage()}.").queue()
                return@launch
            }
            if (nextBlock.tracks.isEmpty()) {
                event.hook.editOriginal("No songs were found for the next block.").queue()
                return@launch
            }
            if (nextBlock.tracks.size < count) {
                event.hook.editOriginal("I only found ${nextBlock.tracks.size}/$count songs for the next `$vibeLabel` block. Try broader seeds or clear history.").queue()
                return@launch
            }
            val appendedShow = existingShow.copy(tracks = existingShow.tracks + nextBlock.tracks)
            session.ensureQueueVibes(existingShow.tracks.size)
            session.queueVibes.addAll(List(nextBlock.tracks.size) { vibeLabel })
            session.preparedShow = appendedShow
            appendPreparedShowToHistory(session, nextBlock)
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            visualizerState.queueUpdated(appendedShow, session.nextTrackIndex)
            event.hook.editOriginal(
                "Appended ${nextBlock.tracks.size} songs for `$vibeLabel`. Queue now has ${appendedShow.tracks.size - session.nextTrackIndex} upcoming tracks." +
                    appendedShow.availabilityNote(localSongs.size)
            ).queue()
        }
    }

    private fun prepareJourney(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/prepare-journey`.").setEphemeral(true).queue()
        event.deferReply().queue()
        scope.launch {
            val count = event.getOption("songs")?.asLong?.toInt()?.coerceIn(6, 12) ?: 12
            val vibeText = event.getOption("vibe")?.asString.orEmpty()
            val vibeLabel = vibeLabelFor(vibeText)
            visualizerState.preparing("$vibeLabel Journey")
            val session = sessionFor(guild.idLong, voiceChannel.idLong)
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val localSongs = library.songs()
            val lastFmClient = config.lastFmApiKey.takeIf { it.isNotBlank() }?.let {
                LastFmClient(apiKeyProvider = { it })
            }
            val recommender = if (lastFmClient != null) {
                lastFmClient
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
            val journeyBeats = JourneyPlanner(config.openAiApiKey, config.openAiTextModel)
                .createJourney(vibeLabel, artists, count)
            val preparer = ShowPreparer(
                stationManager = StationManager(StationRules(candidateCount = 12)),
                musicRecommender = recommender,
                songPicker = openAiClient?.let { FallbackSongPicker(it, localPicker) } ?: localPicker,
                segueWriter = openAiClient?.let { FallbackSegueWriter(it, localSegue) } ?: localSegue,
                trackResolver = DesktopTrackResolver(library, allowUnmatchedPlannedSongs = true),
                trackFactFinder = lastFmClient ?: com.aipirateradio.app.station.EmptyTrackFactFinder
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
                    targetSongCount = count,
                    journeyBeats = journeyBeats
                )
            }.getOrElse {
                event.hook.editOriginal("Journey setup failed: ${it.readableMessage()}.").queue()
                return@launch
            }.withJourneyReasons(journeyBeats)
            if (show.tracks.isEmpty()) {
                event.hook.editOriginal("No songs were found for that journey.").queue()
                return@launch
            }
            session.preparedShow = show
            session.vibeLabel = "$vibeLabel Journey"
            session.queueVibes = MutableList(show.tracks.size) { "$vibeLabel Journey" }
            session.nextTrackIndex = 0
            session.journeyLockedUntilIndex = show.tracks.size
            appendPreparedShowToHistory(session, show)
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            visualizerState.prepared(show, "$vibeLabel Journey")
            event.hook.editOriginal(
                show.summary("Journey prepared") +
                    "\n\nJourney chapters:\n${journeyBeats.formatJourneyBeats()}" +
                    show.availabilityNote(localSongs.size)
            ).queue()
        }
    }

    private fun prepareAlbum(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/prepare-album`.").setEphemeral(true).queue()
        val artist = event.getOption("artist")?.asString.orEmpty().trim()
        val albumTitle = event.getOption("album")?.asString?.trim()?.takeIf { it.isNotBlank() }
        if (artist.isBlank()) {
            return event.reply("Give me an artist for album mode.").setEphemeral(true).queue()
        }
        event.deferReply().queue()
        scope.launch {
            val session = sessionFor(guild.idLong, voiceChannel.idLong)
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val localSongs = library.songs()
            val vibeLabel = albumTitle?.let { "$artist - $it" } ?: "$artist Album"
            visualizerState.preparing(vibeLabel)
            val album = MusicBrainzAlbumClient().findAlbum(artist, albumTitle)
                ?: run {
                    val albumHint = albumTitle?.let { " named `$it`" }.orEmpty()
                    event.hook.editOriginal("I could not find an official MusicBrainz album for `$artist`$albumHint.").queue()
                    return@launch
                }
            val resolver = DesktopTrackResolver(library, allowUnmatchedPlannedSongs = true)
            val resolvedTracks = album.tracks.map { song -> resolver.resolve(song) ?: song }
            val show = PreparedShow(
                tracks = writeAlbumSegues(album, resolvedTracks.mapIndexed { index, song ->
                    PreparedShowTrack(
                        song = song,
                        segueText = null,
                        pickReason = "Album mode: track ${index + 1} from ${album.artist} - ${album.title}."
                    )
                })
            )
            session.preparedShow = show
            session.vibeLabel = "${album.artist} - ${album.title}"
            session.queueVibes = MutableList(show.tracks.size) { "${album.artist} - ${album.title}" }
            session.nextTrackIndex = 0
            session.journeyLockedUntilIndex = show.tracks.size
            appendPreparedShowToHistory(session, show)
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            visualizerState.prepared(show, session.vibeLabel)
            event.hook.editOriginal(
                show.summary("Album prepared: ${album.artist} - ${album.title}") +
                    "\n\nRequests will be saved until after the album." +
                    show.availabilityNote(localSongs.size)
            ).queue()
        }
    }

    private suspend fun prepareBatchShow(
        vibeLabel: String,
        seedArtists: List<String>,
        musicRecommender: MusicRecommender,
        library: DesktopMusicLibrary,
        history: List<PlayRecord>,
        targetSongCount: Int
    ): PreparedShow? {
        if (config.openAiApiKey.isBlank()) return null
        val planner = BatchShowPlanner(config.openAiApiKey, config.openAiTextModel)
        val vibeBrief = planner.createVibeBrief(vibeLabel, seedArtists) ?: return null
        val pool = musicRecommender.buildPool(
            RecommendationRequest(
                favoriteArtists = seedArtists.map { FavoriteArtistSeed(it) },
                includeObscureTracks = true,
                includeBSides = false,
                maxArtistsPerSeed = 10,
                maxTracksPerArtist = 14
            )
        ).songs
        if (pool.size < targetSongCount) return null
        val candidateCount = (targetSongCount * 14).coerceIn(targetSongCount, 168)
        val seedKeys = seedArtists.map { it.cleanRequestKey() }.toSet()
        val stationManager = StationManager(StationRules())
        val now = Instant.now()

        val seedCandidates = if (seedKeys.isNotEmpty()) {
            pool.filter { it.artist.cleanRequestKey() in seedKeys && stationManager.rejectionReason(it, history, now) == null }
                .groupBy { it.artist.cleanRequestKey() }
                .flatMap { (_, songs) -> songs.shuffled().take(3) }
        } else {
            emptyList()
        }
        val seedIds = seedCandidates.map { it.id }.toSet()

        val candidates = (seedCandidates + pool
            .asSequence()
            .filter { it.id !in seedIds }
            .filter { TrackQualityPolicy.isPlayableCatalogTrack(it) }
            .filter { SeasonalMusicPolicy.rejectionReason(it) == null }
            .filter { stationManager.rejectionReason(it, history, now) == null }
            .distinctBy { "${it.artist.cleanRequestKey()}|${it.title.cleanRequestKey()}" }
            .take(candidateCount - seedCandidates.size)
            .toList())
            .mapIndexed { index, song ->
                val seededArtistBonus = if (song.artist.cleanRequestKey() in seedKeys) 30 else 0
                val deepCutBonus = if (song.moodTags.any { it.equals("deep_cut", ignoreCase = true) }) 8 else 0
                Candidate(
                    song = song,
                    score = 100 + seededArtistBonus + deepCutBonus - (index / 4),
                    reasons = listOf(
                        "Vibe pool candidate",
                        if (seededArtistBonus > 0) "Seed artist" else "Adjacent artist",
                        if (deepCutBonus > 0) "Deep cut shelf" else "Familiar shelf"
                    )
                )
            }
            .toList()
        if (candidates.size < targetSongCount) return null
        val picks = planner.planPlaylist(
            vibeLabel = vibeLabel,
            vibeBrief = vibeBrief,
            candidates = candidates,
            history = history,
            targetSongCount = targetSongCount
        ) ?: return null
        val seededPicks = protectSeedArtists(picks, candidates, seedArtists)
        val balancedPicks = balanceBatchPicks(seededPicks, candidates, targetSongCount, planner, vibeBrief)
        val resolver = DesktopTrackResolver(library, allowUnmatchedPlannedSongs = true)
        val tracks = balancedPicks.map { pick ->
            PreparedShowTrack(
                song = resolver.resolve(pick.song) ?: pick.song,
                segueText = null,
                pickReason = "Batch vibe brief: $vibeBrief Pick: ${pick.reason}"
            )
        }
        val segues = planner.writeSegueBatch(vibeBrief, tracks, spacing = 2).orEmpty()
        return PreparedShow(
            tracks = tracks.mapIndexed { index, track ->
                track.copy(segueText = segues[index])
            }
        )
    }

    private suspend fun balanceBatchPicks(
        picks: List<BatchPickedSong>,
        candidates: List<Candidate>,
        targetSongCount: Int,
        planner: BatchShowPlanner,
        vibeBrief: String
    ): List<BatchPickedSong> {
        val artistCap = if (targetSongCount >= 10) 2 else 1
        val familyCap = if (targetSongCount >= 10) 2 else 2
        val usedSongKeys = mutableSetOf<String>()
        val artistCounts = mutableMapOf<String, Int>()
        val familyCounts = mutableMapOf<String, Int>()
        val kept = mutableListOf<BatchPickedSong>()
        val removed = mutableListOf<BatchPickedSong>()
        picks.forEach { pick ->
            val artistKey = pick.song.artist.cleanRequestKey()
            val familyKey = ArtistGroups.familyKey(pick.song.artist)
            val currentCount = artistCounts.getOrDefault(artistKey, 0)
            val currentFamilyCount = familyCounts.getOrDefault(familyKey, 0)
            if (currentCount < artistCap && currentFamilyCount < familyCap) {
                artistCounts[artistKey] = currentCount + 1
                familyCounts[familyKey] = currentFamilyCount + 1
                kept += pick
                usedSongKeys += pick.song.songRequestKey()
            } else {
                removed += pick
            }
        }

        val replacementCandidates = candidates.filter { candidate ->
            val artistKey = candidate.song.artist.cleanRequestKey()
            val familyKey = ArtistGroups.familyKey(candidate.song.artist)
            candidate.song.songRequestKey() !in usedSongKeys &&
                artistCounts.getOrDefault(artistKey, 0) < artistCap &&
                familyCounts.getOrDefault(familyKey, 0) < familyCap
        }
        val fillIns = planner.chooseFillIns(
            vibeBrief = vibeBrief,
            currentPicks = kept,
            candidates = replacementCandidates,
            fillCount = removed.size
        ).orEmpty()
        addBalancedFillIns(kept, fillIns, artistCounts, familyCounts, usedSongKeys, artistCap, familyCap)

        if (kept.size < targetSongCount) {
            val localFillIns = replacementCandidates
                .filter { it.song.songRequestKey() !in usedSongKeys }
                .map { BatchPickedSong(it.song, "Local fill-in after repeat cleanup.") }
            addBalancedFillIns(kept, localFillIns, artistCounts, familyCounts, usedSongKeys, artistCap, familyCap)
        }

        if (kept.size < targetSongCount) {
            val remainingCandidates = candidates
                .filter { it.song.songRequestKey() !in usedSongKeys }
                .sortedByDescending { it.score }

            remainingCandidates.forEach { candidate ->
                if (kept.size >= targetSongCount) return@forEach
                val artistKey = candidate.song.artist.cleanRequestKey()
                val familyKey = ArtistGroups.familyKey(candidate.song.artist)
                if (artistCounts.getOrDefault(artistKey, 0) < artistCap &&
                    familyCounts.getOrDefault(familyKey, 0) < familyCap) {
                    kept += BatchPickedSong(candidate.song, "Fallback variety fill-in.")
                    usedSongKeys += candidate.song.songRequestKey()
                    artistCounts[artistKey] = artistCounts.getOrDefault(artistKey, 0) + 1
                    familyCounts[familyKey] = familyCounts.getOrDefault(familyKey, 0) + 1
                }
            }
        }

        if (kept.size < targetSongCount) {
            removed.forEach { pick ->
                if (kept.size >= targetSongCount || pick.song.songRequestKey() in usedSongKeys) return@forEach
                kept += pick.copy(reason = "${pick.reason} Repeat allowed because the adjacent pool was narrow.")
                usedSongKeys += pick.song.songRequestKey()
            }
        }

        return spreadArtistRepeats(kept.take(targetSongCount))
    }

    private fun addBalancedFillIns(
        target: MutableList<BatchPickedSong>,
        fillIns: List<BatchPickedSong>,
        artistCounts: MutableMap<String, Int>,
        familyCounts: MutableMap<String, Int>,
        usedSongKeys: MutableSet<String>,
        artistCap: Int,
        familyCap: Int
    ) {
        fillIns.forEach { fillIn ->
            val artistKey = fillIn.song.artist.cleanRequestKey()
            val familyKey = ArtistGroups.familyKey(fillIn.song.artist)
            val songKey = fillIn.song.songRequestKey()
            if (songKey in usedSongKeys) return@forEach
            if (artistCounts.getOrDefault(artistKey, 0) >= artistCap) return@forEach
            if (familyCounts.getOrDefault(familyKey, 0) >= familyCap) return@forEach
            target += fillIn
            usedSongKeys += songKey
            artistCounts[artistKey] = artistCounts.getOrDefault(artistKey, 0) + 1
            familyCounts[familyKey] = familyCounts.getOrDefault(familyKey, 0) + 1
        }
    }

    private fun protectSeedArtists(
        picks: List<BatchPickedSong>,
        candidates: List<Candidate>,
        seedArtists: List<String>
    ): List<BatchPickedSong> {
        if (seedArtists.isEmpty() || picks.isEmpty()) return picks
        val result = picks.toMutableList()
        val usedSongKeys = result.map { it.song.songRequestKey() }.toMutableSet()
        seedArtists.map { it.cleanRequestKey() }.distinct().forEach { seedKey ->
            if (result.any { it.song.artist.cleanRequestKey() == seedKey }) return@forEach
            val seedCandidate = candidates.firstOrNull { candidate ->
                candidate.song.artist.cleanRequestKey() == seedKey &&
                    candidate.song.songRequestKey() !in usedSongKeys
            } ?: return@forEach
            val replaceIndex = result.indexOfBestSeedReplacement(seedArtists)
            if (replaceIndex < 0) return@forEach
            usedSongKeys.remove(result[replaceIndex].song.songRequestKey())
            result[replaceIndex] = BatchPickedSong(
                song = seedCandidate.song,
                reason = "Seed artist included to keep the requested vibe anchored."
            )
            usedSongKeys += seedCandidate.song.songRequestKey()
        }
        return result
    }

    private fun List<BatchPickedSong>.indexOfBestSeedReplacement(seedArtists: List<String>): Int {
        val seedKeys = seedArtists.map { it.cleanRequestKey() }.toSet()
        val familyCounts = groupingBy { ArtistGroups.familyKey(it.song.artist) }.eachCount()
        val artistCounts = groupingBy { it.song.artist.cleanRequestKey() }.eachCount()
        return indices
            .filter { this[it].song.artist.cleanRequestKey() !in seedKeys }
            .maxByOrNull { index ->
                val song = this[index].song
                artistCounts.getOrDefault(song.artist.cleanRequestKey(), 0) * 10 +
                    familyCounts.getOrDefault(ArtistGroups.familyKey(song.artist), 0)
            }
            ?: indices.maxByOrNull { index ->
                val song = this[index].song
                artistCounts.getOrDefault(song.artist.cleanRequestKey(), 0) * 10 +
                    familyCounts.getOrDefault(ArtistGroups.familyKey(song.artist), 0)
            }
            ?: -1
    }

    private fun spreadArtistRepeats(picks: List<BatchPickedSong>): List<BatchPickedSong> {
        val remaining = picks.toMutableList()
        val result = mutableListOf<BatchPickedSong>()
        while (remaining.isNotEmpty()) {
            val recentArtists = result.takeLast(2).map { it.song.artist.cleanRequestKey() }.toSet()
            val recentFamilies = result.takeLast(3).map { ArtistGroups.familyKey(it.song.artist) }.toSet()
            val best = remaining
                .withIndex()
                .maxByOrNull { indexed ->
                    val artistKey = indexed.value.song.artist.cleanRequestKey()
                    val familyKey = ArtistGroups.familyKey(indexed.value.song.artist)
                    val remainingSameArtist = remaining.count { it.song.artist.cleanRequestKey() == artistKey }
                    val remainingSameFamily = remaining.count { ArtistGroups.familyKey(it.song.artist) == familyKey }
                    val lastSameArtistIndex = result.indexOfLast { it.song.artist.cleanRequestKey() == artistKey }
                    val lastSameFamilyIndex = result.indexOfLast { ArtistGroups.familyKey(it.song.artist) == familyKey }
                    val distance = if (lastSameArtistIndex == -1) 99 else result.lastIndex - lastSameArtistIndex
                    val familyDistance = if (lastSameFamilyIndex == -1) 99 else result.lastIndex - lastSameFamilyIndex
                    val recentPenalty = if (artistKey in recentArtists) 100 else 0
                    val recentFamilyPenalty = if (familyKey in recentFamilies) 40 else 0
                    remainingSameArtist * 12 + remainingSameFamily * 6 + distance + familyDistance - recentPenalty - recentFamilyPenalty
                }
                ?: break
            result += best.value
            remaining.removeAt(best.index)
        }
        return result
    }

    private suspend fun writeAlbumSegues(album: MusicBrainzAlbum, tracks: List<PreparedShowTrack>): List<PreparedShowTrack> {
        if (tracks.isEmpty()) return tracks
        val localSegue = LocalSegueWriter()
        val openAiClient = config.openAiApiKey.takeIf { it.isNotBlank() }?.let {
            OpenAiRadioClient(
                apiKeyProvider = { it },
                textModel = config.openAiTextModel,
                generateSpeech = false
            )
        }
        val segueWriter = openAiClient?.let { FallbackSegueWriter(it, localSegue) } ?: localSegue
        val factFinder = config.lastFmApiKey.takeIf { it.isNotBlank() }?.let {
            LastFmClient(apiKeyProvider = { it })
        } ?: com.aipirateradio.app.station.EmptyTrackFactFinder
        val showSongs = tracks.map { it.song }
        val showTheme = "Full album mode: ${album.artist} - ${album.title}. Respect the album sequence and frame it like one record taking over the booth."
        return tracks.mapIndexed { index, track ->
            if (index > 0 && index % 4 != 0) return@mapIndexed track
            val facts = runCatching { factFinder.factsFor(track.song) }.getOrDefault(emptyList())
            val segue = segueWriter.writeSegue(
                SegueRequest(
                    song = track.song,
                    type = if (index == 0) SegueType.THEME else SegueType.TRANSITION,
                    previousSong = tracks.getOrNull(index - 1)?.song,
                    showTheme = showTheme,
                    isNewArtist = index == 0,
                    verifiedFacts = facts,
                    showSongs = showSongs
                )
            )
            track.copy(segueText = segue?.text)
        }
    }

    private fun prepareLocal(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/prepare-local`.").setEphemeral(true).queue()
        event.deferReply().queue()
        scope.launch {
            val count = event.getOption("songs")?.asLong?.toInt()?.coerceIn(1, 24) ?: 12
            val session = sessionFor(guild.idLong, voiceChannel.idLong)
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val localSongs = library.songs()
            if (localSongs.isEmpty()) {
                event.hook.editOriginal("No local music found. Set `MUSIC_LIBRARY_PATH` to a folder with audio files, then restart the bot.").queue()
                return@launch
            }
            visualizerState.preparing("Local Library")
            val localPicker = LocalSongPicker()
            val localSegue = LocalSegueWriter()
            val lastFmClient = config.lastFmApiKey.takeIf { it.isNotBlank() }?.let {
                LastFmClient(apiKeyProvider = { it })
            }
            val openAiClient = config.openAiApiKey.takeIf { it.isNotBlank() }?.let {
                OpenAiRadioClient(
                    apiKeyProvider = { it },
                    textModel = config.openAiTextModel,
                    generateSpeech = false
                )
            }
            val preparer = ShowPreparer(
                stationManager = StationManager(StationRules(candidateCount = 12)),
                musicRecommender = DesktopMusicRecommender(library),
                songPicker = openAiClient?.let { FallbackSongPicker(it, localPicker) } ?: localPicker,
                segueWriter = openAiClient?.let { FallbackSegueWriter(it, localSegue) } ?: localSegue,
                trackResolver = DesktopTrackResolver(library),
                trackFactFinder = lastFmClient ?: com.aipirateradio.app.station.EmptyTrackFactFinder
            )
            val show = runCatching {
                preparer.prepareShow(
                    recommendationRequest = RecommendationRequest(
                        favoriteArtists = emptyList(),
                        includeObscureTracks = true,
                        includeBSides = false,
                        maxArtistsPerSeed = 0,
                        maxTracksPerArtist = 0
                    ),
                    startingHistory = session.history,
                    startingSegueState = SegueState(),
                    targetSongCount = count.coerceAtMost(localSongs.size)
                )
            }.getOrElse {
                event.hook.editOriginal("Local show setup failed: ${it.readableMessage()}.").queue()
                return@launch
            }
            if (show.tracks.isEmpty()) {
                event.hook.editOriginal("No playable local songs could be selected. Try clearing history or adding more music.").queue()
                return@launch
            }
            session.preparedShow = show
            session.vibeLabel = "Local Library"
            session.queueVibes = MutableList(show.tracks.size) { "Local Library" }
            session.nextTrackIndex = 0
            session.journeyLockedUntilIndex = 0
            appendPreparedShowToHistory(session, show)
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            visualizerState.prepared(show, "Local Library")
            event.hook.editOriginal(show.summary("Prepared local-only") + show.availabilityNote(localSongs.size)).queue()
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
        val startIndex = session.nextTrackIndex.coerceIn(0, show.tracks.size)
        if (startIndex >= show.tracks.size) {
            return event.reply("This show is already finished. Run `/prepare` for a new show.").setEphemeral(true).queue()
        }
        guild.audioManager.sendingHandler = session.sendHandler
        guild.audioManager.openAudioConnection(voiceChannel)
        session.djUserId = event.user.idLong
        session.voiceChannelId = voiceChannel.idLong
        session.isPlaying = true
        event.reply(if (startIndex == 0) "Starting the show in ${voiceChannel.name}." else "Resuming the show in ${voiceChannel.name} at ${startIndex + 1}/${show.tracks.size}.").queue()
        scope.launch {
            session.player.resetForPlayback()
            val speech = speechSynthesizer()
            var index = startIndex
            while (true) {
                val currentShow = session.preparedShow ?: break
                if (index >= currentShow.tracks.size) break
                val track = resolveTrackForPlayback(session, currentShow, index)
                    ?: run {
                        pauseAtMissingTrack(session, guild.idLong, voiceChannel, index, currentShow.tracks[index], event)
                        return@launch
                    }
                val playbackShow = session.preparedShow ?: currentShow
                if (session.player.isStopped()) return@launch
                val trackVibe = session.vibeLabelAt(index)
                session.vibeLabel = trackVibe
                session.nextTrackIndex = (index + 1).coerceAtMost(playbackShow.tracks.size)
                store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
                answerCallInIfQueued(session, playbackShow, index, trackVibe, speech, event)
                if (session.player.isStopped()) return@launch
                track.segueText?.takeIf { it.isNotBlank() }?.let {
                    visualizerState.djLine(it)
                    event.channel.sendMessage("DJ: $it").queue()
                    playDjSegue(session, speech, it, event)
                }
                if (session.player.isStopped()) return@launch
                visualizerState.onAir(playbackShow, index, track.segueText, trackVibe)
                event.channel.sendMessage("Now playing ${index + 1}/${playbackShow.tracks.size}: ${track.song.artist} - ${track.song.title}").queue()
                updateVoiceChannelStatus(voiceChannel, "Now playing: ${track.song.artist} - ${track.song.title}")
                val played = session.player.play(track.song)
                if (session.player.isStopped()) return@launch
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
                    pauseAtMissingTrack(session, guild.idLong, voiceChannel, index, track, event)
                    return@launch
                }
                index += 1
            }
            session.isPlaying = false
            session.djUserId = null
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            val latestShow = session.preparedShow
            if (latestShow != null && session.nextTrackIndex >= latestShow.tracks.size) {
                writeClosingSegue(latestShow, session.vibeLabel)?.takeIf { it.isNotBlank() }?.let {
                    visualizerState.djLine(it)
                    event.channel.sendMessage("DJ: $it").queue()
                    playDjSegue(session, speech, it, event)
                }
                updateVoiceChannelStatus(voiceChannel, null)
                visualizerState.complete()
                event.channel.sendMessage("Show complete.").queue()
            }
        }
    }

    private suspend fun resolveTrackForPlayback(
        session: GuildRadioSession,
        show: PreparedShow,
        index: Int
    ): PreparedShowTrack? {
        val track = show.tracks[index]
        if (track.song.localPathOrNull()?.let { Files.isRegularFile(it) } == true) return track

        val resolver = DesktopTrackResolver(DesktopMusicLibrary(config.musicLibraryPath))
        val resolvedSong = resolver.resolve(track.song)
        if (resolvedSong?.localPathOrNull()?.let { Files.isRegularFile(it) } != true) return null

        val updatedTrack = track.copy(song = resolvedSong)
        session.preparedShow = show.copy(
            tracks = show.tracks.toMutableList().also { tracks ->
                tracks[index] = updatedTrack
            }
        )
        return updatedTrack
    }

    private fun pauseAtMissingTrack(
        session: GuildRadioSession,
        guildId: Long,
        voiceChannel: AudioChannel,
        index: Int,
        track: PreparedShowTrack,
        event: SlashCommandInteractionEvent
    ) {
        session.player.stop()
        session.nextTrackIndex = index
        session.isPlaying = false
        session.djUserId = null
        store.saveSession(RadioSessionKey(guildId, voiceChannel.idLong), session)
        updateVoiceChannelStatus(voiceChannel, "Paused: missing ${track.song.artist} - ${track.song.title}")
        visualizerState.paused("Missing: ${track.song.artist} - ${track.song.title}")
        event.guild?.audioManager?.closeAudioConnection()
        event.channel.sendMessage(
            "Paused at missing track ${index + 1}: ${track.song.artist} - ${track.song.title}.\n" +
                "Run `/download-missing`, then `/play` to resume from here."
        ).queue()
    }

    private suspend fun writeClosingSegue(show: PreparedShow, vibe: String): String? {
        val lastTrack = show.tracks.lastOrNull() ?: return null
        val localSegue = LocalSegueWriter()
        val openAiClient = config.openAiApiKey.takeIf { it.isNotBlank() }?.let {
            OpenAiRadioClient(
                apiKeyProvider = { it },
                textModel = config.openAiTextModel,
                generateSpeech = false
            )
        }
        val segueWriter = openAiClient?.let { FallbackSegueWriter(it, localSegue) } ?: localSegue
        return segueWriter.writeSegue(
            SegueRequest(
                song = lastTrack.song,
                type = SegueType.CLOSING,
                previousSong = show.tracks.dropLast(1).lastOrNull()?.song,
                showTheme = "Closing sign-off for this Radio Skittles set. Current vibe: $vibe.",
                isNewArtist = false,
                showSongs = show.tracks.map { it.song }
            )
        )?.text
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
            Next track: `${session?.nextTrackLabel() ?: "none"}`
            Saved history: `${session?.history?.size ?: 0} / ${config.historyLimit} songs`
            """.trimIndent()
        ).setEphemeral(true).queue()
    }

    private fun queue(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/queue`.").setEphemeral(true).queue()
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        val show = session.preparedShow
            ?: return event.reply("No show prepared yet.").setEphemeral(true).queue()
        val localSongs = DesktopMusicLibrary(config.musicLibraryPath).songs().size
        event.reply(show.summary("Queue") + "\n\nNext up: ${session.nextTrackLabel()}" + show.availabilityNote(localSongs)).queue()
    }

    private fun requestSong(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/request`.").setEphemeral(true).queue()
        val rawRequest = event.getOption("song")?.asString.orEmpty()
        event.deferReply().queue()
        scope.launch {
            val session = sessionFor(guild.idLong, voiceChannel.idLong)
            val show = session.preparedShow
                ?: run {
                    event.hook.editOriginal("Prepare a show first, then requests can be worked into it.").queue()
                    return@launch
                }
            val now = Instant.now()
            val lastRequestAt = session.requestCooldowns[event.user.idLong]
            if (lastRequestAt != null) {
                val remaining = REQUEST_COOLDOWN.minus(Duration.between(lastRequestAt, now))
                if (!remaining.isNegative && !remaining.isZero) {
                    event.hook.editOriginal("You're still in the request cooldown. Try again in ${remaining.toMinutes().coerceAtLeast(1)} minute(s).").queue()
                    return@launch
                }
            }
            val planner = RequestPlanner(config.openAiApiKey, config.openAiTextModel)
            val request = planner.parse(rawRequest)
                ?: run {
                    event.hook.editOriginal("I could not understand that request. Try `Artist - Song` or `Song by Artist`.").queue()
                    return@launch
                }
            val library = DesktopMusicLibrary(config.musicLibraryPath)
            val requestedSong = resolveRequestedSong(request, library, show, session.history)
                ?: run {
                    event.hook.editOriginal("I can take an artist request, but I could not find a song for `${request.displayName()}`. Try `Artist - Song` if you have one in mind.").queue()
                    return@launch
                }
            val localSong = requestedSong.takeIf { it.localPathOrNull() != null }
            SeasonalMusicPolicy.rejectionReason(requestedSong, now)?.let {
                event.hook.editOriginal("I hear the request for ${requestedSong.artist} - ${requestedSong.title}, but I am not emotionally prepared for holiday music yet. Try it again once the lights go up.").queue()
                return@launch
            }
            if (show.hasSong(requestedSong) || session.history.hasSong(requestedSong)) {
                event.hook.editOriginal("That one has already been through the booth recently or is already in the queue. Try a different song.").queue()
                return@launch
            }
            val requestVibe = session.vibeLabelAt(session.nextTrackIndex)
            val fitDecision = planner.evaluateVibeFit(show, session.nextTrackIndex, requestVibe, requestedSong)
            if (fitDecision?.isRejected == true) {
                val reason = fitDecision.reason.takeIf { it.isNotBlank() } ?: "it is pulling too far away from this block's vibe"
                event.hook.editOriginal("I hear the request for ${requestedSong.artist} - ${requestedSong.title}, but $reason. Try it again next show.").queue()
                return@launch
            }
            val journeyProtected = session.isJourneyProtected()
            val insertIndex = if (journeyProtected) {
                show.tracks.size
            } else {
                planner.chooseInsertionIndex(show, session.nextTrackIndex, requestedSong)
                    ?: defaultRequestInsertIndex(session.nextTrackIndex, show.tracks.size, fitDecision)
            }
            val artistRequestLabel = request.takeIf { it.isArtistOnly }?.displayName()
            val requestSegue = RequestSegueWriter(config.openAiApiKey, config.openAiTextModel).write(
                song = requestedSong,
                currentVibe = requestVibe,
                fitDecision = fitDecision,
                requesterName = event.user.effectiveName,
                artistRequest = artistRequestLabel,
                playful = requestedSong.deservesSillyRequestSegue()
            ) ?: requestSegueText(requestedSong, fitDecision, artistRequestLabel)
            val requestTrack = PreparedShowTrack(
                song = requestedSong,
                segueText = requestSegue,
                pickReason = if (request.isArtistOnly) {
                    "Listener request: ${event.user.effectiveName} asked for ${request.displayName()}, so the station picked this track."
                } else {
                    "Listener request: ${event.user.effectiveName} asked for this one."
                }
            )
            val updatedShow = show.copy(
                tracks = show.tracks.toMutableList().also { tracks ->
                    tracks.add(insertIndex.coerceIn(session.nextTrackIndex.coerceAtLeast(0), tracks.size), requestTrack)
                }
            )
            val actualInsertIndex = insertIndex.coerceIn(session.nextTrackIndex.coerceAtLeast(0), updatedShow.tracks.size - 1)
            session.preparedShow = updatedShow
            session.ensureQueueVibes(show.tracks.size)
            session.queueVibes.add(actualInsertIndex, requestVibe)
            session.requestCooldowns[event.user.idLong] = now
            store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
            visualizerState.queueUpdated(updatedShow, session.nextTrackIndex)
            val position = actualInsertIndex + 1
            val readyText = if (localSong != null) {
                "It is already local and ready to play."
            } else {
                "I do not see it locally yet. Run `/download-missing` to try fetching it, then `/refresh` if needed."
            }
            val fitText = fitDecision?.fit?.takeIf { it.isNotBlank() }?.let { " Fit: $it." }.orEmpty()
            val pickedText = if (request.isArtistOnly) {
                "Picked ${requestedSong.artist} - ${requestedSong.title} for `${request.displayName()}`."
            } else {
                "Request added"
            }
            val journeyText = if (journeyProtected) " Saved until after the journey." else ""
            event.hook.editOriginal("$pickedText Added at #$position.$fitText$journeyText $readyText").queue()
        }
    }

    private suspend fun resolveRequestedSong(
        request: ListenerSongRequest,
        library: DesktopMusicLibrary,
        show: PreparedShow,
        history: List<PlayRecord>
    ): Song? {
        if (!request.isArtistOnly) {
            return library.findRequestedSong(request) ?: request.toPlannedSong()
        }

        val artistName = request.artist?.trim()?.takeIf { it.isNotBlank() } ?: return null
        library.findArtistRequestSong(artistName, show, history)?.let { return it }
        library.findRequestedSong(ListenerSongRequest(artist = null, title = artistName, isArtistOnly = false))
            ?.takeIf { song -> !show.hasSong(song) && !history.hasSong(song) }
            ?.let { return it }

        val lastFmSongs = LastFmClient(apiKeyProvider = { config.lastFmApiKey }).topSongsForArtist(artistName, limit = 12)
        return lastFmSongs.firstOrNull { song ->
            !show.hasSong(song) && !history.hasSong(song)
        }
    }

    private fun askDj(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join a voice channel first, then run `/ask`.").setEphemeral(true).queue()
        val question = event.getOption("question")?.asString.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (question.length < 4) {
            return event.reply("Give me a little more to work with.").setEphemeral(true).queue()
        }
        if (question.length > 240) {
            return event.reply("Keep call-ins under 240 characters so the DJ can answer between songs.").setEphemeral(true).queue()
        }
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        val now = Instant.now()
        val lastAskAt = session.askCooldowns[event.user.idLong]
        if (lastAskAt != null) {
            val remaining = ASK_COOLDOWN.minus(Duration.between(lastAskAt, now))
            if (!remaining.isNegative && !remaining.isZero) {
                return event.reply("You're still in the call-in cooldown. Try again in ${remaining.toMinutes().coerceAtLeast(1)} minute(s).").setEphemeral(true).queue()
            }
        }
        if (session.callIns.size >= MAX_CALL_IN_QUEUE) {
            return event.reply("The caller board is full right now. Try again after the DJ clears a question or two.").setEphemeral(true).queue()
        }
        session.callIns += CallInQuestion(
            userId = event.user.idLong,
            displayName = event.member?.effectiveName ?: event.user.name,
            question = question,
            askedAt = now
        )
        session.askCooldowns[event.user.idLong] = now
        store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
        event.reply("You're on the caller board. I'll work that in between songs.").queue()
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

    private suspend fun playDjSegue(
        session: GuildRadioSession,
        speech: (String) -> Path?,
        text: String,
        event: SlashCommandInteractionEvent
    ) {
        repeat(2) { attempt ->
            if (session.player.isStopped()) return
            val speechFile = speech(text)
            if (speechFile == null || !Files.isRegularFile(speechFile) || Files.size(speechFile) == 0L) {
                runCatching { if (speechFile != null) Files.deleteIfExists(speechFile) }
                if (session.player.isStopped()) return
                if (attempt == 0) {
                    delay(500)
                    return@repeat
                }
                event.channel.sendMessage("DJ voice could not be generated on this computer.").queue()
                return
            }

            val spoke = session.player.playFile(speechFile.toString())
            runCatching { Files.deleteIfExists(speechFile) }
            if (session.player.isStopped()) return
            if (spoke) return
            if (attempt == 0) {
                delay(500)
            } else {
                event.channel.sendMessage("DJ voice could not play. Check `/status` for ffmpeg.").queue()
            }
        }
    }

    private suspend fun answerCallInIfQueued(
        session: GuildRadioSession,
        show: PreparedShow,
        index: Int,
        vibe: String,
        speech: (String) -> Path?,
        event: SlashCommandInteractionEvent
    ) {
        val callIn = session.callIns.removeFirstOrNull() ?: return
        val answer = CallInResponder(config.openAiApiKey, config.openAiTextModel).answer(
            question = callIn,
            vibe = vibe,
            nowPlaying = show.tracks.getOrNull(index - 1)?.song,
            upNext = show.tracks.getOrNull(index)?.song
        )
        store.saveSession(RadioSessionKey(event.guild!!.idLong, session.voiceChannelId ?: 0L), session)
        val line = "Caller ${callIn.displayName} asks: ${callIn.question} $answer"
        visualizerState.djLine(line)
        event.channel.sendMessage("Caller ${callIn.displayName}: ${callIn.question}\nDJ: $answer").queue()
        playDjSegue(session, speech, line, event)
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
        store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
        updateVoiceChannelStatus(voiceChannel, "Paused: ${session.nextTrackLabel()}")
        visualizerState.paused(session.nextTrackLabel())
        guild.audioManager.closeAudioConnection()
        event.reply("Radio stopped.").queue()
    }

    private fun pause(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.reply("Use this in a server.").setEphemeral(true).queue()
        val voiceChannel = event.member?.voiceState?.channel
            ?: return event.reply("Join the voice channel you want to pause first.").setEphemeral(true).queue()
        val session = sessionFor(guild.idLong, voiceChannel.idLong)
        if (session.djUserId != null && session.djUserId != event.user.idLong) {
            return event.reply("Only this channel's DJ, <@${session.djUserId}>, can pause this show.").setEphemeral(true).queue()
        }
        session.player.stop()
        session.isPlaying = false
        session.djUserId = null
        store.saveSession(RadioSessionKey(guild.idLong, voiceChannel.idLong), session)
        updateVoiceChannelStatus(voiceChannel, "Paused: ${session.nextTrackLabel()}")
        visualizerState.paused(session.nextTrackLabel())
        guild.audioManager.closeAudioConnection()
        event.reply("Paused. `/play` will resume at ${session.nextTrackLabel()}.").queue()
    }

    private fun updateVoiceChannelStatus(channel: AudioChannel, status: String?) {
        val voiceStatusChannel = channel as? IVoiceStatusChannel ?: return
        val safeStatus = status
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(IVoiceStatusChannel.MAX_STATUS_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: ""
        voiceStatusChannel.modifyStatus(safeStatus).queue(
            {},
            {}
        )
    }

    private fun sessionFor(guildId: Long, voiceChannelId: Long): GuildRadioSession =
        sessions.getOrPut(RadioSessionKey(guildId, voiceChannelId)) {
            val persisted = store.loadSession(RadioSessionKey(guildId, voiceChannelId))
            val handler = DiscordPcmSendHandler()
            GuildRadioSession(
                sendHandler = handler,
                player = DiscordAudioPlayer(handler),
                preparedShow = persisted.preparedShow,
                history = persisted.history.toMutableList(),
                nextTrackIndex = persisted.nextTrackIndex,
                vibeLabel = persisted.vibeLabel,
                queueVibes = persisted.queueVibes.toMutableList(),
                requestCooldowns = persisted.requestCooldowns.toMutableMap(),
                callIns = persisted.callIns.toMutableList(),
                askCooldowns = persisted.askCooldowns.toMutableMap(),
                journeyLockedUntilIndex = persisted.journeyLockedUntilIndex
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

    private fun vibeLabelFor(vibeText: String): String {
        if (vibeText.isBlank()) return RadioVibes.default.name
        val matchingVibe = RadioVibes.all.firstOrNull { it.name.equals(vibeText, ignoreCase = true) || it.id.equals(vibeText, ignoreCase = true) }
        if (matchingVibe != null) return matchingVibe.name
        val artists = vibeText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return artists.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: RadioVibes.default.name
    }
}

private fun DesktopMusicLibrary.findRequestedSong(request: ListenerSongRequest): Song? {
    val title = request.title?.takeIf { it.isNotBlank() } ?: return null
    val songs = songs()
    val titleKey = title.cleanRequestKey()
    val artistKey = request.artist?.cleanRequestKey()
    return songs.firstOrNull { song ->
        song.title.cleanRequestKey() == titleKey &&
            (artistKey == null || song.artist.cleanRequestKey() == artistKey)
    } ?: songs.firstOrNull { song ->
        song.title.cleanRequestKey().contains(titleKey) &&
            (artistKey == null || song.artist.cleanRequestKey().contains(artistKey))
    }
}

private fun DesktopMusicLibrary.findArtistRequestSong(artist: String, show: PreparedShow, history: List<PlayRecord>): Song? {
    val artistKey = artist.cleanRequestKey()
    if (artistKey.isBlank()) return null
    val candidates = songs()
        .filter { song ->
            val songArtistKey = song.artist.cleanRequestKey()
            songArtistKey == artistKey || songArtistKey.contains(artistKey) || artistKey.contains(songArtistKey)
        }
        .filterNot { song -> show.hasSong(song) || history.hasSong(song) }
    return candidates.firstOrNull()
}

private fun ListenerSongRequest.toPlannedSong(): Song {
    val artistName = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
    val titleName = title?.takeIf { it.isNotBlank() } ?: return Song(
        id = "request-artist:${artistName}".stableRequestId(),
        audioUri = "",
        title = "Station pick",
        artist = artistName,
        duration = Duration.ofMinutes(3),
        genreTags = listOf("request")
    )
    return Song(
        id = "request:${artistName}:${titleName}".stableRequestId(),
        audioUri = "",
        title = titleName,
        artist = artistName,
        duration = Duration.ofMinutes(3),
        genreTags = listOf("request")
    )
}

private fun String.cleanRequestKey(): String {
    return lowercase()
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\[[^]]*]"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

private fun String.stableRequestId(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun PreparedShow.hasSong(song: Song): Boolean =
    tracks.any { it.song.sameRequestedSong(song) }

private fun List<PlayRecord>.hasSong(song: Song): Boolean =
    any { it.artist.cleanRequestKey() == song.artist.cleanRequestKey() && it.title.cleanRequestKey() == song.title.cleanRequestKey() }

private fun Song.sameRequestedSong(other: Song): Boolean =
    artist.cleanRequestKey() == other.artist.cleanRequestKey() && title.cleanRequestKey() == other.title.cleanRequestKey()

private fun Song.songRequestKey(): String =
    "${artist.cleanRequestKey()}|${title.cleanRequestKey()}"

private fun GuildRadioSession.isJourneyProtected(): Boolean =
    journeyLockedUntilIndex > 0 && nextTrackIndex < journeyLockedUntilIndex

private fun PreparedShow.withJourneyReasons(beats: List<JourneyBeat>): PreparedShow {
    if (beats.isEmpty()) return this
    return copy(
        tracks = tracks.mapIndexed { index, track ->
            val beat = beats.getOrNull(index) ?: return@mapIndexed track
            val beatReason = "Journey chapter ${beat.number}: ${beat.description} (${beat.word})"
            val existing = track.pickReason?.takeIf { it.isNotBlank() }
            track.copy(pickReason = if (existing == null) beatReason else "$beatReason $existing")
        }
    )
}

private fun List<JourneyBeat>.formatJourneyBeats(): String =
    joinToString("\n") { beat -> "${beat.number}. ${beat.description} (${beat.word})" }

private fun defaultRequestInsertIndex(nextTrackIndex: Int, trackCount: Int, fitDecision: RequestFitDecision?): Int {
    val offset = if (fitDecision?.isStretch == true) 3 else 1
    return (nextTrackIndex + offset).coerceIn(nextTrackIndex.coerceAtLeast(0), trackCount)
}

private fun requestSegueText(song: Song, fitDecision: RequestFitDecision?, artistRequest: String? = null): String {
    val requestIntro = if (artistRequest.isNullOrBlank()) {
        "We had a request come through for ${song.artist} with ${song.title}"
    } else {
        "We had an artist request come through for $artistRequest, and ${song.artist} with ${song.title} is where the station landed"
    }
    return if (fitDecision?.isStretch == true) {
        if (song.deservesSillyRequestSegue()) {
            "$requestIntro. It is absolutely ridiculous, which is not a flaw so much as a signed permission slip."
        } else {
            "$requestIntro. It bends the shape of this set a little, but sometimes a curveball earns its place."
        }
    } else {
        "$requestIntro."
    }
}

private fun Song.deservesSillyRequestSegue(): Boolean {
    val text = "${artist} ${title} ${album.orEmpty()} ${genreTags.joinToString(" ")} ${moodTags.joinToString(" ")}".cleanRequestKey()
    return SILLY_METAL_TERMS.any { text.contains(it) }
}

private val SILLY_METAL_TERMS = listOf(
    "gloryhammer",
    "alestorm",
    "angus mcsix",
    "rumahoy",
    "victorius",
    "dinosaur",
    "laser",
    "hoots",
    "wizard",
    "dragon",
    "pirate",
    "cowboys of the sea",
    "diggy diggy hole"
)

private val REQUEST_COOLDOWN: Duration = Duration.ofMinutes(20)
private val ASK_COOLDOWN: Duration = Duration.ofMinutes(15)
private const val MAX_CALL_IN_QUEUE = 5

data class RadioSessionKey(val guildId: Long, val voiceChannelId: Long)

data class CallInQuestion(
    val userId: Long,
    val displayName: String,
    val question: String,
    val askedAt: Instant
)

data class GuildRadioSession(
    val sendHandler: DiscordPcmSendHandler,
    val player: DiscordAudioPlayer,
    var preparedShow: PreparedShow? = null,
    val history: MutableList<PlayRecord> = mutableListOf(),
    var nextTrackIndex: Int = 0,
    var vibeLabel: String = RadioVibes.default.name,
    var queueVibes: MutableList<String> = mutableListOf(),
    val requestCooldowns: MutableMap<Long, Instant> = mutableMapOf(),
    val callIns: MutableList<CallInQuestion> = mutableListOf(),
    val askCooldowns: MutableMap<Long, Instant> = mutableMapOf(),
    var journeyLockedUntilIndex: Int = 0,
    var djUserId: Long? = null,
    var voiceChannelId: Long? = null,
    var isPlaying: Boolean = false
)

private fun GuildRadioSession.nextTrackLabel(): String {
    val show = preparedShow ?: return "none"
    if (show.tracks.isEmpty()) return "none"
    val index = nextTrackIndex.coerceIn(0, show.tracks.size)
    if (index >= show.tracks.size) return "show complete"
    val song = show.tracks[index].song
    return "${index + 1}/${show.tracks.size}: ${song.artist} - ${song.title}"
}

private fun GuildRadioSession.vibeLabelAt(index: Int): String {
    ensureQueueVibes(preparedShow?.tracks?.size ?: queueVibes.size)
    return queueVibes.getOrNull(index)?.takeIf { it.isNotBlank() } ?: vibeLabel
}

private fun GuildRadioSession.ensureQueueVibes(trackCount: Int) {
    if (trackCount <= 0) {
        queueVibes.clear()
        return
    }
    if (queueVibes.size > trackCount) {
        queueVibes = queueVibes.take(trackCount).toMutableList()
    }
    while (queueVibes.size < trackCount) {
        queueVibes += vibeLabel
    }
}

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
