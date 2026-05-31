package com.aipirateradio.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aipirateradio.app.local.AndroidLocalAudioPlayer
import com.aipirateradio.app.local.AndroidLocalDjAnnouncer
import com.aipirateradio.app.local.LocalMusicLibrary
import com.aipirateradio.app.local.LocalMusicRecommender
import com.aipirateradio.app.local.LocalTrackResolver
import com.aipirateradio.app.openai.AndroidTtsAudioPlayer
import com.aipirateradio.app.openai.FallbackSegueWriter
import com.aipirateradio.app.openai.FallbackSongPicker
import com.aipirateradio.app.openai.LocalSegueWriter
import com.aipirateradio.app.openai.LocalSongPicker
import com.aipirateradio.app.openai.OpenAiRadioClient
import com.aipirateradio.app.openai.SilentTtsPlayer
import com.aipirateradio.app.recommendations.FavoriteArtistSeed
import com.aipirateradio.app.recommendations.LastFmClient
import com.aipirateradio.app.recommendations.RecommendationRequest
import com.aipirateradio.app.recommendations.StaticSeedRecommender
import com.aipirateradio.app.station.PlayRecord
import com.aipirateradio.app.station.PreparedShow
import com.aipirateradio.app.station.RadioVibe
import com.aipirateradio.app.station.RadioVibes
import com.aipirateradio.app.station.SampleCatalog
import com.aipirateradio.app.station.SavedShowStore
import com.aipirateradio.app.station.SegueState
import com.aipirateradio.app.station.ShowHistoryStore
import com.aipirateradio.app.station.ShowPreparer
import com.aipirateradio.app.station.StationDecision
import com.aipirateradio.app.station.StationEngine
import com.aipirateradio.app.station.StationManager
import com.aipirateradio.app.station.StationRules
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

@Composable
fun PirateRadioApp(
    requestLocalAudioAccess: ((String) -> Unit) -> Unit = {}
) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize().background(Color(0xFFF7F4EE)),
            color = Color(0xFFF7F4EE)
        ) {
            StationScreen(requestLocalAudioAccess = requestLocalAudioAccess)
        }
    }
}

@Composable
private fun StationScreen(
    requestLocalAudioAccess: ((String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<PlayRecord>() }
    var latestDecision by remember { mutableStateOf<StationDecision?>(null) }
    var segueState by remember { mutableStateOf(SegueState(songsSinceLastSegue = 4)) }
    var status by remember { mutableStateOf("Ready") }
    var musicStatus by remember { mutableStateOf("Local music idle") }
    var djStatus by remember { mutableStateOf("DJ idle") }
    var showStatus by remember { mutableStateOf("No show prepared") }
    val savedShowStore = remember(context) { SavedShowStore(context) }
    val showHistoryStore = remember(context) { ShowHistoryStore(context) }
    var preparedShow by remember { mutableStateOf(savedShowStore.load()) }
    var selectedVibe by remember { mutableStateOf(RadioVibes.default) }
    val lastFmApiKey = remember(context) { context.getString(com.aipirateradio.app.R.string.lastfm_api_key) }
    val openAiApiKey = remember(context) { context.getString(com.aipirateradio.app.R.string.openai_api_key) }
    val openAiTextModel = remember(context) { context.getString(com.aipirateradio.app.R.string.openai_text_model) }
    val openAiTtsModel = remember(context) { context.getString(com.aipirateradio.app.R.string.openai_tts_model) }
    val localLibrary = remember(context) { LocalMusicLibrary(context) }
    val localPlayer = remember(context) { AndroidLocalAudioPlayer(context) { musicStatus = it } }
    val localDjAnnouncer = remember(context) { AndroidLocalDjAnnouncer(context) { djStatus = it } }

    LaunchedEffect(Unit) {
        preparedShow?.let { show -> showStatus = "Loaded saved show with ${show.tracks.size} songs." }
    }

    Column(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("AI Pirate Radio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1F2933))

        NowPlayingCard(
            status = status,
            musicStatus = musicStatus,
            djStatus = djStatus,
            showStatus = showStatus,
            decision = latestDecision,
            preparedShow = preparedShow,
            selectedVibe = selectedVibe,
            onVibeSelected = { selectedVibe = it },
            onGrantMusic = {
                requestLocalAudioAccess { message -> musicStatus = message }
            },
            onRefreshMusic = {
                scope.launch {
                    val songs = localLibrary.songs()
                    musicStatus = "Found ${songs.size} local songs."
                    preparedShow?.let { show ->
                        val resolver = LocalTrackResolver(localLibrary)
                        val refreshedShow = show.copy(
                            tracks = show.tracks.map { track ->
                                track.copy(song = resolver.resolve(track.song) ?: track.song)
                            }
                        )
                        preparedShow = refreshedShow
                        savedShowStore.save(refreshedShow)
                        val missing = refreshedShow.tracks.count { !it.song.isLocalAudio() }
                        showStatus = if (missing == 0) {
                            "Offline show ready with ${refreshedShow.tracks.size} local songs."
                        } else {
                            "Refreshed show: ${refreshedShow.tracks.size - missing} ready, $missing need downloading."
                        }
                    }
                }
            },
            onPlayShow = {
                scope.launch {
                    val show = preparedShow
                    if (show == null || show.tracks.isEmpty()) {
                        showStatus = "Prepare a show before playback."
                        return@launch
                    }
                    if (!localLibrary.hasAudioPermission()) {
                        showStatus = "Grant local music access before playback."
                        requestLocalAudioAccess { message -> musicStatus = message }
                        return@launch
                    }
                    showStatus = "Playing prepared local show."
                    var played = 0
                    var missing = 0
                    show.tracks.forEach { track ->
                        val playableSong = LocalTrackResolver(localLibrary).resolve(track.song)
                        if (playableSong == null || !playableSong.isLocalAudio()) {
                            missing += 1
                            showStatus = "Missing local file: ${track.song.artist} - ${track.song.title}."
                        } else {
                            localDjAnnouncer.speak(track.segueText)
                            localPlayer.play(playableSong)
                            localPlayer.awaitTrackEnd(playableSong)
                            played += 1
                        }
                    }
                    showStatus = if (played == 0) {
                        "No local files matched this show. Download the Needed songs, then Refresh."
                    } else {
                        "Show complete: played $played, missing $missing."
                    }
                }
            },
            onPlaySegues = {
                scope.launch {
                    val show = preparedShow
                    if (show == null || show.tracks.isEmpty()) {
                        showStatus = "Prepare a show before testing segues."
                        return@launch
                    }
                    val segues = show.tracks.mapIndexedNotNull { index, track -> track.segueText?.takeIf { it.isNotBlank() }?.let { index to it } }
                    if (segues.isEmpty()) {
                        showStatus = "This show has no saved DJ segues yet."
                        return@launch
                    }
                    showStatus = "Playing ${segues.size} saved DJ segues."
                    segues.forEach { (index, text) ->
                        djStatus = "Testing segue ${index + 1} of ${show.tracks.size}."
                        localDjAnnouncer.speak(text)
                    }
                    djStatus = "DJ segue test complete."
                    showStatus = "Finished testing ${segues.size} segues."
                }
            },
            onCopySegues = {
                val show = preparedShow
                if (show == null || show.tracks.isEmpty()) {
                    showStatus = "Prepare a show before copying segues."
                    return@NowPlayingCard
                }
                val segueText = show.formatSeguesForSharing()
                if (segueText.isBlank()) {
                    showStatus = "This show has no saved DJ segues to copy."
                    return@NowPlayingCard
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AI Pirate Radio segues", segueText))
                showStatus = "Copied ${show.tracks.count { !it.segueText.isNullOrBlank() }} segues to clipboard."
            },
            onClearHistory = {
                showHistoryStore.clear()
                history.clear()
                showStatus = "Show history cleared. Future plans can reuse songs again."
            },
            onPrepareShow = {
                scope.launch {
                    val localSongs = if (localLibrary.hasAudioPermission()) localLibrary.songs() else emptyList()
                    val planningHistory = showHistoryStore.recentPlayRecords()
                    showStatus = if (planningHistory.isEmpty()) {
                        "Preparing a 12-song show plan."
                    } else {
                        "Preparing a 12-song show plan with ${planningHistory.size} recent planned songs avoided."
                    }
                    val recommender = if (lastFmApiKey.isNotBlank()) {
                        musicStatus = "Using Last.fm to plan the show."
                        LastFmClient(apiKeyProvider = { lastFmApiKey.trim() })
                    } else if (localSongs.size >= 6) {
                        musicStatus = "Planning from ${localSongs.size} local songs."
                        LocalMusicRecommender(localLibrary)
                    } else {
                        musicStatus = "Using sample catalog for the show plan."
                        StaticSeedRecommender(SampleCatalog.songs)
                    }
                    val localPicker = LocalSongPicker()
                    val localSegue = LocalSegueWriter()
                    val openAiClient = if (openAiApiKey.isBlank()) {
                        djStatus = "OpenAI key missing. Using local DJ."
                        null
                    } else {
                        djStatus = "OpenAI DJ preparing the show."
                        OpenAiRadioClient(
                            apiKeyProvider = { openAiApiKey.trim() },
                            textModel = openAiTextModel.ifBlank { "gpt-4o-mini" },
                            ttsModel = openAiTtsModel.ifBlank { "gpt-4o-mini-tts" },
                            generateSpeech = false
                        )
                    }
                    val preparer = ShowPreparer(
                        stationManager = StationManager(StationRules(candidateCount = 12)),
                        musicRecommender = recommender,
                        songPicker = if (openAiClient == null) localPicker else FallbackSongPicker(openAiClient, localPicker) { djStatus = it },
                        segueWriter = if (openAiClient == null) localSegue else FallbackSegueWriter(openAiClient, localSegue) { djStatus = it },
                        trackResolver = LocalTrackResolver(localLibrary.takeIf { localLibrary.hasAudioPermission() }, allowUnmatchedPlannedSongs = true)
                    )
                    val show = runCatching {
                        preparer.prepareShow(
                            recommendationRequest = RecommendationRequest(
                                favoriteArtists = selectedVibe.artists.map { FavoriteArtistSeed(it) },
                                includeObscureTracks = true,
                                includeBSides = false,
                                maxArtistsPerSeed = 3,
                                maxTracksPerArtist = 2
                            ),
                            startingHistory = planningHistory + history,
                            startingSegueState = segueState,
                            targetSongCount = 12
                        )
                    }.getOrElse {
                        showStatus = "Show setup failed: ${it.readableMessage()}."
                        return@launch
                    }
                    preparedShow = show
                    savedShowStore.save(show)
                    showHistoryStore.recordPreparedShow(show, selectedVibe.id)
                    if (show.tracks.isEmpty()) {
                        showStatus = "No songs were found for the show plan."
                        return@launch
                    }
                    val missing = show.tracks.count { !it.song.isLocalAudio() }
                    showStatus = if (missing == 0) {
                        "Offline show ready with ${show.tracks.size} local songs."
                    } else {
                        "Show plan ready: ${show.tracks.size} songs, $missing need downloading."
                    }
                }
            },
            onNext = {
                scope.launch {
                    status = "Selecting candidates"
                    if (!localLibrary.hasAudioPermission()) {
                        status = "Grant local music access first"
                        requestLocalAudioAccess { message -> musicStatus = message }
                        return@launch
                    }
                    val localSongs = localLibrary.songs()
                    if (localSongs.isEmpty()) {
                        status = "No local music found"
                        return@launch
                    }
                    val openAiClient = if (openAiApiKey.isBlank()) null else OpenAiRadioClient(
                        apiKeyProvider = { openAiApiKey.trim() },
                        textModel = openAiTextModel.ifBlank { "gpt-4o-mini" },
                        ttsModel = openAiTtsModel.ifBlank { "gpt-4o-mini-tts" }
                    )
                    val localPicker = LocalSongPicker()
                    val localSegue = LocalSegueWriter()
                    val engine = StationEngine(
                        stationManager = StationManager(),
                        musicRecommender = LocalMusicRecommender(localLibrary),
                        songPicker = if (openAiClient == null) localPicker else FallbackSongPicker(openAiClient, localPicker) { djStatus = it },
                        segueWriter = if (openAiClient == null) localSegue else FallbackSegueWriter(openAiClient, localSegue) { djStatus = it },
                        ttsPlayer = if (openAiClient == null) SilentTtsPlayer() else AndroidTtsAudioPlayer(context) { djStatus = it },
                        trackResolver = LocalTrackResolver(),
                        audioPlayer = localPlayer
                    )
                    val decision = runCatching {
                        engine.chooseAndPlayNext(
                            RecommendationRequest(selectedVibe.artists.map { FavoriteArtistSeed(it) }),
                            history,
                            segueState
                        )
                    }.getOrElse {
                        status = "Selection failed: ${it.readableMessage()}."
                        return@launch
                    }
                    latestDecision = decision
                    if (decision == null) {
                        status = "No playable song selected"
                        return@launch
                    }
                    history += PlayRecord(decision.selectedSong.id, decision.selectedSong.title, decision.selectedSong.artist, decision.selectedSong.album, decision.selectedSong.genreTags, Instant.now(), hadSegue = decision.shouldSegue)
                    segueState = if (decision.shouldSegue) SegueState(0, true) else SegueState(segueState.songsSinceLastSegue + 1, false)
                    status = "Playing"
                }
            }
        )

        DecisionPanel(decision = latestDecision)
    }
}

@Composable
private fun NowPlayingCard(
    status: String,
    musicStatus: String,
    djStatus: String,
    showStatus: String,
    decision: StationDecision?,
    preparedShow: PreparedShow?,
    selectedVibe: RadioVibe,
    onVibeSelected: (RadioVibe) -> Unit,
    onGrantMusic: () -> Unit,
    onRefreshMusic: () -> Unit,
    onPlayShow: () -> Unit,
    onPlaySegues: () -> Unit,
    onCopySegues: () -> Unit,
    onClearHistory: () -> Unit,
    onPrepareShow: () -> Unit,
    onNext: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(status, style = MaterialTheme.typography.labelLarge, color = Color(0xFF35605A))
            Text(musicStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7A4E2D))
            Text(djStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF52616B))
            Text(showStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF35605A))
            Text(decision?.selectedSong?.title ?: "No song selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(decision?.selectedSong?.artist ?: "Tap Next Pick to let the Station Manager choose.", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF52616B))
            VibePicker(selectedVibe, onVibeSelected)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onGrantMusic, modifier = Modifier.weight(1f)) { Text("Grant Music") }
                    Button(onRefreshMusic, modifier = Modifier.weight(1f)) { Text("Refresh") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onPrepareShow, modifier = Modifier.weight(1f)) { Text("Prepare Show") }
                    Button(onNext, modifier = Modifier.weight(1f)) { Text("Next Pick") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onPlayShow, modifier = Modifier.weight(1f)) { Text("Play Show") }
                    Button(onPlaySegues, modifier = Modifier.weight(1f)) { Text("Play Segues") }
                }
                Button(onCopySegues, modifier = Modifier.fillMaxWidth()) { Text("Copy Segues") }
                Button(onClearHistory, modifier = Modifier.fillMaxWidth()) { Text("Clear History") }
            }
            preparedShow?.takeIf { it.tracks.isNotEmpty() }?.let { show ->
                Text("${show.tracks.size} songs prepared for offline playback", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF52616B))
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(show.tracks.size) { index ->
                        val track = show.tracks[index]
                        val prefix = if (track.song.isLocalAudio()) "Ready" else "Need"
                        Text("${index + 1}. $prefix: ${track.song.artist} - ${track.song.title}", style = MaterialTheme.typography.bodySmall, color = if (track.song.isLocalAudio()) Color(0xFF35605A) else Color(0xFF7A4E2D))
                    }
                }
            }
        }
    }
}

@Composable
private fun VibePicker(selectedVibe: RadioVibe, onVibeSelected: (RadioVibe) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Vibe", style = MaterialTheme.typography.labelLarge, color = Color(0xFF35605A))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedVibe.name) }
        Button(onClick = {
            val choices = RadioVibes.all.filterNot { it.id == selectedVibe.id }.ifEmpty { RadioVibes.all }
            onVibeSelected(choices.random(Random.Default))
        }, modifier = Modifier.fillMaxWidth()) { Text("Random Vibe") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RadioVibes.all.forEach { vibe ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(vibe.name, fontWeight = FontWeight.Bold)
                            Text(vibe.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF52616B))
                        }
                    },
                    onClick = {
                        onVibeSelected(vibe)
                        expanded = false
                    }
                )
            }
        }
        Text(selectedVibe.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF52616B))
        Text(selectedVibe.artists.joinToString(" / "), style = MaterialTheme.typography.bodySmall, color = Color(0xFF7A4E2D))
    }
}

@Composable
private fun DecisionPanel(decision: StationDecision?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Station Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1F2933))
        if (decision == null) {
            Text("Waiting for the first decision.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF52616B))
            return
        }
        Text(if (decision.shouldSegue) "Segue approved" else "Segue skipped", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF7A4E2D))
        Text(decision.llmReason ?: "Local fallback selected the track.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF52616B))
        Spacer(modifier = Modifier.height(2.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(decision.candidates) { candidate ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF2EF))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(candidate.song.title, fontWeight = FontWeight.Bold)
                        Text(candidate.song.artist, color = Color(0xFF52616B))
                        Text("Score ${candidate.score}", color = Color(0xFF35605A))
                    }
                }
            }
        }
    }
}

private fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
private fun com.aipirateradio.app.station.Song.isLocalAudio(): Boolean = audioUri.startsWith("content://") || audioUri.startsWith("file://")
private fun PreparedShow.formatSeguesForSharing(): String {
    return tracks.mapIndexedNotNull { index, track ->
        val segue = track.segueText?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        """
        ${index + 1}. ${track.song.artist} - ${track.song.title}
        $segue
        """.trimIndent()
    }.joinToString(separator = "\n\n")
}
