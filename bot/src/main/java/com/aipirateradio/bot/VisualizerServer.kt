package com.aipirateradio.bot

import com.aipirateradio.app.station.PreparedShow
import com.aipirateradio.app.station.PreparedShowTrack
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RadioVisualizerState {
    private val lock = Any()
    private var snapshot = VisualizerSnapshot()

    fun preparing(vibe: String) = update {
        copy(status = "Preparing", vibe = vibe, djLine = "Building the next set.", nowPlaying = null, upNext = emptyList())
    }

    fun prepared(show: PreparedShow, vibe: String) = update {
        copy(
            status = "Ready",
            vibe = vibe,
            nowPlaying = null,
            djLine = "Show prepared.",
            upNext = show.tracks.take(5).map { it.toVisualizerTrack() },
            progressStartedAtEpochMs = null,
            progressDurationMs = null
        )
    }

    fun onAir(show: PreparedShow, index: Int, djLine: String? = snapshot().djLine, vibe: String? = null) = update {
        val track = show.tracks.getOrNull(index)
        copy(
            status = "On Air",
            vibe = vibe?.takeIf { it.isNotBlank() } ?: this.vibe,
            nowPlaying = track?.toVisualizerTrack(),
            djLine = djLine?.takeIf { it.isNotBlank() } ?: this.djLine,
            upNext = show.tracks.drop(index + 1).take(5).map { it.toVisualizerTrack() },
            progressStartedAtEpochMs = Instant.now().toEpochMilli(),
            progressDurationMs = track?.song?.duration?.toMillis()
        )
    }

    fun queueUpdated(show: PreparedShow, nextTrackIndex: Int) = update {
        copy(
            upNext = show.tracks
                .drop(nextTrackIndex.coerceIn(0, show.tracks.size))
                .take(5)
                .map { it.toVisualizerTrack() }
        )
    }

    fun djLine(text: String) = update {
        copy(djLine = text)
    }

    fun paused(nextTrack: String) = update {
        copy(status = "Paused", djLine = "Paused. Next up: $nextTrack", progressStartedAtEpochMs = null)
    }

    fun complete() = update {
        copy(status = "Complete", djLine = "Show complete.", nowPlaying = null, upNext = emptyList(), progressStartedAtEpochMs = null)
    }

    fun snapshot(): VisualizerSnapshot = synchronized(lock) { snapshot }

    private fun update(block: VisualizerSnapshot.() -> VisualizerSnapshot) {
        synchronized(lock) {
            snapshot = snapshot.block()
        }
    }
}

class VisualizerServer(
    private val port: Int,
    private val state: RadioVisualizerState,
    coverCacheDirectory: Path,
    lastFmApiKey: String
) {
    private var server: HttpServer? = null
    private val coverArtCache = CoverArtCache(coverCacheDirectory, lastFmApiKey)
    private val assetRoot = Path.of("activity")

    fun start() {
        if (port <= 0 || server != null) return
        val httpServer = runCatching {
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        }.getOrElse {
            println("Visualizer could not start on http://localhost:$port (${it.message}).")
            return
        }
        httpServer.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/", "/index.html" -> exchange.respondFile(assetRoot.resolve("index.html"), "text/html; charset=utf-8")
                "/styles.css" -> exchange.respondFile(assetRoot.resolve("styles.css"), "text/css; charset=utf-8")
                "/config.js" -> exchange.respondFile(assetRoot.resolve("config.js"), "application/javascript; charset=utf-8")
                "/app.js" -> exchange.respondFile(assetRoot.resolve("app.js"), "application/javascript; charset=utf-8")
                "/api/state", "/state" -> exchange.respond("application/json; charset=utf-8", state.snapshot().toJson().toString(), allowCors = true)
                "/api/cover/current", "/cover/current" -> exchange.respondCurrentCover(state.snapshot().nowPlaying, coverArtCache)
                else -> exchange.respond("text/plain; charset=utf-8", "Not found", 404)
            }
        }
        httpServer.executor = Executors.newSingleThreadExecutor()
        httpServer.start()
        server = httpServer
    }
}

data class VisualizerSnapshot(
    val stationName: String = "Radio Skittles",
    val status: String = "Idle",
    val vibe: String = "Emotional Authentic Rock",
    val nowPlaying: VisualizerTrack? = null,
    val djLine: String = "Waiting for the signal.",
    val upNext: List<VisualizerTrack> = emptyList(),
    val progressStartedAtEpochMs: Long? = null,
    val progressDurationMs: Long? = null
)

data class VisualizerTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
    val localPath: String? = null,
    val pickReason: String = DEFAULT_PICK_REASON
)

private fun PreparedShowTrack.toVisualizerTrack(): VisualizerTrack =
    VisualizerTrack(
        title = song.title,
        artist = song.artist,
        album = song.album,
        durationMs = song.duration.toMillis(),
        localPath = song.localPathOrNull()?.toString(),
        pickReason = pickReason?.takeIf { it.isNotBlank() } ?: DEFAULT_PICK_REASON
    )

private fun VisualizerSnapshot.toJson(): JSONObject {
    return JSONObject()
        .put("stationName", stationName)
        .put("status", status)
        .put("vibe", vibe)
        .put("djLine", djLine)
        .put("nowPlaying", nowPlaying?.toJson())
        .put("upNext", JSONArray().also { array -> upNext.forEach { array.put(it.toJson()) } })
        .put("progressStartedAtEpochMs", progressStartedAtEpochMs)
        .put("progressDurationMs", progressDurationMs)
}

private fun VisualizerTrack.toJson(): JSONObject =
    JSONObject()
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationMs", durationMs)
        .put("pickReason", pickReason)
        .put("coverUrl", localPath?.let { "/api/cover/current?v=${it.stableHash()}" })

private fun HttpExchange.respond(contentType: String, body: String, status: Int = 200, allowCors: Boolean = false) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", contentType)
    responseHeaders.set("Cache-Control", "no-store")
    if (allowCors) responseHeaders.set("Access-Control-Allow-Origin", "*")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.respondFile(path: Path, contentType: String) {
    if (!Files.isRegularFile(path)) {
        respond("text/plain; charset=utf-8", "Missing visualizer asset: ${path.fileName}", 404)
        return
    }
    val bytes = Files.readAllBytes(path)
    responseHeaders.set("Content-Type", contentType)
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.respondCurrentCover(track: VisualizerTrack?, coverArtCache: CoverArtCache) {
    val cover = track?.let { coverArtCache.coverFor(it) }
    if (cover == null) {
        respond("text/plain; charset=utf-8", "No cover art", 404, allowCors = true)
        return
    }
    val bytes = Files.readAllBytes(cover.path)
    responseHeaders.set("Content-Type", cover.contentType)
    responseHeaders.set("Cache-Control", "public, max-age=3600")
    responseHeaders.set("Access-Control-Allow-Origin", "*")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private class CoverArtCache(
    private val root: Path,
    private val lastFmApiKey: String
) {
    private val httpClient = HttpClient.newHttpClient()

    fun coverFor(track: VisualizerTrack): CoverArt? {
        Files.createDirectories(root)
        lastFmCoverFor(track)?.let { return it }
        val audioFile = track.localPath?.let { Path.of(it) } ?: return null
        if (!Files.isRegularFile(audioFile)) return null
        nearbyCoverFor(audioFile)?.let { return it }
        val cacheKey = "${audioFile.toAbsolutePath()}|${Files.getLastModifiedTime(audioFile).toMillis()}".stableHash()
        val output = root.resolve("$cacheKey.jpg")
        if (Files.isRegularFile(output) && Files.size(output) > 0) return CoverArt(output, "image/jpeg")

        val embedded = extractEmbeddedCover(audioFile, output, explicitMap = true)
            ?: extractEmbeddedCover(audioFile, output, explicitMap = false)
        return embedded?.takeIf { Files.isRegularFile(it) && Files.size(it) > 0 }?.let { CoverArt(it, "image/jpeg") }
    }

    private fun lastFmCoverFor(track: VisualizerTrack): CoverArt? {
        if (lastFmApiKey.isBlank()) return null
        val cacheKey = "lastfm|${track.artist}|${track.album.orEmpty()}|${track.title}".stableHash()
        val output = root.resolve("$cacheKey.jpg")
        if (Files.isRegularFile(output) && Files.size(output) > 0) return CoverArt(output, "image/jpeg")

        val imageUrl = lastFmTrackImage(track) ?: lastFmAlbumImage(track) ?: return null
        val request = HttpRequest.newBuilder(URI(imageUrl)).GET().build()
        val response = runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }.getOrNull() ?: return null
        if (response.statusCode() !in 200..299 || response.body().isEmpty()) return null
        Files.write(output, response.body())
        return CoverArt(output, response.headers().firstValue("Content-Type").orElse("image/jpeg"))
    }

    private fun lastFmTrackImage(track: VisualizerTrack): String? {
        val json = getLastFmJson(
            "track.getInfo",
            mapOf("artist" to track.artist, "track" to track.title)
        ) ?: return null
        return json.optJSONObject("track")
            ?.optJSONObject("album")
            ?.optJSONArray("image")
            .largestLastFmImage()
    }

    private fun lastFmAlbumImage(track: VisualizerTrack): String? {
        val album = track.album?.takeIf { it.isNotBlank() } ?: return null
        val json = getLastFmJson(
            "album.getInfo",
            mapOf("artist" to track.artist, "album" to album)
        ) ?: return null
        return json.optJSONObject("album")
            ?.optJSONArray("image")
            .largestLastFmImage()
    }

    private fun getLastFmJson(method: String, params: Map<String, String>): JSONObject? {
        val query = buildString {
            append("method=").append(method)
            params.forEach { (key, value) ->
                append('&').append(key).append('=').append(value.urlEncoded())
            }
            append("&api_key=").append(lastFmApiKey.urlEncoded())
            append("&format=json")
        }
        val request = HttpRequest.newBuilder(URI("https://ws.audioscrobbler.com/2.0/?$query")).GET().build()
        return runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                if (response.statusCode() in 200..299) JSONObject(response.body()) else null
            }
        }.getOrNull()
    }

    private fun extractEmbeddedCover(audioFile: Path, output: Path, explicitMap: Boolean): Path? {
        val command = mutableListOf(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            audioFile.toString()
        )
        if (explicitMap) command += listOf("-map", "0:v:0")
        command += listOf("-frames:v", "1", output.toString())
        val process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        return output.takeIf { finished && process.exitValue() == 0 && Files.isRegularFile(it) && Files.size(it) > 0 }
    }

    private fun nearbyCoverFor(audioFile: Path): CoverArt? {
        val directory = audioFile.parent ?: return null
        val candidates = listOf(
            "cover.png",
            "cover.jpg",
            "cover.jpeg",
            "folder.png",
            "folder.jpg",
            "folder.jpeg",
            "front.png",
            "front.jpg",
            "front.jpeg"
        )
        return candidates
            .map { directory.resolve(it) }
            .firstOrNull { Files.isRegularFile(it) && Files.size(it) > 0 }
            ?.let { CoverArt(it, if (it.fileName.toString().endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg") }
    }
}

private data class CoverArt(
    val path: Path,
    val contentType: String
)

private fun JSONArray?.largestLastFmImage(): String? {
    if (this == null) return null
    for (size in listOf("mega", "extralarge", "large", "medium", "small")) {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val url = item.optString("#text").takeIf { it.isNotBlank() } ?: continue
            if (item.optString("size").equals(size, ignoreCase = true)) return url
        }
    }
    return null
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

private fun String.stableHash(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private const val DEFAULT_PICK_REASON = "Chosen from your local library based on freshness, artist spacing, and station flow."

private fun visualizerHtml(): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Radio Skittles</title>
  <style>
    :root {
      color-scheme: dark;
      --pink: #ff4fd8;
      --blue: #26c6ff;
      --violet: #8f5cff;
      --green: #50ff9a;
      --panel: rgba(6, 10, 24, 0.72);
      --line: rgba(143, 92, 255, 0.38);
      font-family: Inter, Segoe UI, system-ui, sans-serif;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      overflow: hidden;
      color: #eef2ff;
      background:
        radial-gradient(circle at 20% 20%, rgba(255, 79, 216, 0.22), transparent 28%),
        radial-gradient(circle at 80% 30%, rgba(38, 198, 255, 0.18), transparent 25%),
        linear-gradient(180deg, #050714 0%, #090b1e 46%, #06040d 100%);
    }
    .city {
      position: fixed;
      inset: 0;
      opacity: 0.55;
      background:
        linear-gradient(transparent 60%, rgba(4, 3, 10, 0.95)),
        repeating-linear-gradient(90deg, transparent 0 42px, rgba(255,255,255,0.04) 43px 45px);
    }
    .city::after {
      content: "";
      position: absolute;
      left: 20%;
      right: 12%;
      bottom: 20%;
      height: 32%;
      background:
        linear-gradient(to top, #03040a 0 56%, transparent 56%),
        repeating-linear-gradient(90deg, transparent 0 20px, rgba(255, 120, 224, 0.45) 21px 24px, transparent 25px 40px);
      clip-path: polygon(0 100%, 0 45%, 6% 45%, 6% 70%, 10% 70%, 10% 35%, 16% 35%, 16% 60%, 21% 60%, 21% 22%, 29% 22%, 29% 68%, 35% 68%, 35% 10%, 43% 10%, 43% 78%, 49% 78%, 49% 32%, 56% 32%, 56% 66%, 62% 66%, 62% 44%, 70% 44%, 70% 80%, 76% 80%, 76% 54%, 84% 54%, 84% 72%, 92% 72%, 92% 42%, 100% 42%, 100% 100%);
      filter: drop-shadow(0 0 18px rgba(255, 79, 216, 0.55));
    }
    main {
      position: relative;
      z-index: 1;
      min-height: 100vh;
      padding: clamp(24px, 4vw, 54px);
      display: grid;
      grid-template-columns: minmax(320px, 0.95fr) minmax(360px, 1.25fr) minmax(280px, 0.75fr);
      gap: 24px;
      align-items: center;
    }
    .stack { display: grid; gap: 22px; }
    .panel {
      border: 1px solid var(--line);
      background: var(--panel);
      box-shadow: 0 0 32px rgba(86, 46, 190, 0.18), inset 0 0 28px rgba(38, 198, 255, 0.04);
      border-radius: 18px;
      padding: 24px;
      backdrop-filter: blur(12px);
    }
    .brand {
      padding: 30px;
      text-transform: uppercase;
      letter-spacing: 0.12em;
    }
    h1 {
      margin: 0;
      font-size: clamp(48px, 6vw, 96px);
      line-height: 0.92;
      color: transparent;
      -webkit-text-stroke: 2px #ff83df;
      text-shadow: 0 0 18px rgba(255, 79, 216, 0.8);
    }
    .radio {
      margin-top: 14px;
      color: var(--blue);
      font-size: clamp(24px, 3vw, 44px);
      letter-spacing: 0.36em;
      text-shadow: 0 0 14px rgba(38, 198, 255, 0.85);
    }
    .label {
      color: #bdc5ff;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      font-size: 14px;
      font-weight: 700;
    }
    .song {
      display: grid;
      grid-template-columns: 160px 1fr;
      gap: 22px;
      align-items: center;
    }
    .cover {
      aspect-ratio: 1;
      border-radius: 12px;
      background:
        linear-gradient(160deg, rgba(255,79,216,.32), transparent 42%),
        radial-gradient(circle at 50% 70%, #ff3b7d 0 20%, transparent 21%),
        linear-gradient(180deg, #101946, #050818 58%, #221036);
      box-shadow: inset 0 0 30px rgba(255,255,255,.04), 0 0 28px rgba(255,79,216,.22);
      display: grid;
      place-items: center;
      color: #ff8de7;
      font-weight: 800;
      text-align: center;
      padding: 16px;
      overflow: hidden;
      position: relative;
    }
    .cover img {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: none;
    }
    .cover.has-art img {
      display: block;
    }
    .cover.has-art span {
      display: none;
    }
    .title {
      margin: 10px 0 6px;
      font-size: clamp(28px, 3vw, 44px);
      font-weight: 800;
    }
    .artist { color: #cfd6ff; font-size: 24px; }
    .progress {
      margin-top: 22px;
      height: 7px;
      border-radius: 999px;
      background: rgba(143, 92, 255, 0.22);
      overflow: hidden;
    }
    .time-row {
      margin-top: 10px;
      display: flex;
      justify-content: space-between;
      color: #9da6da;
      font-size: 14px;
      font-variant-numeric: tabular-nums;
    }
    .progress span {
      display: block;
      width: 0%;
      height: 100%;
      border-radius: inherit;
      background: linear-gradient(90deg, var(--pink), var(--blue));
      box-shadow: 0 0 14px var(--pink);
    }
    .bars {
      height: 340px;
      display: flex;
      align-items: end;
      justify-content: center;
      gap: 8px;
      padding-bottom: 38px;
    }
    .bar {
      width: 12px;
      min-height: 12px;
      border-radius: 4px 4px 0 0;
      background: linear-gradient(180deg, #ff69df, #7a4cff);
      box-shadow: 0 0 14px rgba(255, 79, 216, .5);
      animation: bounce 1.4s ease-in-out infinite;
    }
    @keyframes bounce {
      0%, 100% { height: 36px; }
      50% { height: var(--h); }
    }
    .onair {
      display: inline-flex;
      justify-content: center;
      border: 2px solid #ff634f;
      color: #ff907c;
      padding: 12px 22px;
      border-radius: 10px;
      font-size: 34px;
      letter-spacing: .08em;
      text-transform: uppercase;
      box-shadow: 0 0 20px rgba(255, 78, 58, .72), inset 0 0 14px rgba(255, 78, 58, .22);
      text-shadow: 0 0 12px rgba(255, 100, 80, .9);
    }
    .meta {
      color: #9da6da;
      line-height: 1.55;
      font-size: 18px;
    }
    .queue { display: grid; gap: 14px; }
    .queue-item {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 12px;
      color: #dfe4ff;
    }
    .small { color: #8994ca; font-size: 14px; }
    footer {
      position: fixed;
      z-index: 2;
      left: clamp(24px, 4vw, 54px);
      right: clamp(24px, 4vw, 54px);
      bottom: 22px;
      border: 1px solid rgba(38,198,255,.18);
      background: rgba(3, 7, 18, .62);
      border-radius: 14px;
      padding: 14px 24px;
      display: flex;
      justify-content: space-between;
      color: #6ee8db;
      letter-spacing: .12em;
      text-transform: uppercase;
    }
  </style>
</head>
<body>
  <div class="city"></div>
  <main>
    <section class="stack">
      <div class="panel brand">
        <h1>Radio</h1>
        <div class="radio">Skittles</div>
      </div>
      <div class="panel">
        <div class="label">Now Playing</div>
        <div class="song">
          <div class="cover" id="cover"><img id="coverImage" alt=""><span id="coverText">RADIO<br>SKITTLES</span></div>
          <div>
            <div class="title" id="title">Waiting for signal</div>
            <div class="artist" id="artist">Start the bot show</div>
            <div class="progress"><span id="progress"></span></div>
            <div class="time-row"><span id="elapsed">0:00</span><span id="duration">0:00</span></div>
          </div>
        </div>
      </div>
      <div class="panel">
        <div class="label">Current Vibe</div>
        <p class="meta" id="vibe">Emotional Authentic Rock</p>
      </div>
    </section>
    <section>
      <div class="bars" id="bars"></div>
      <div class="panel" style="text-align:center">
        <div class="label" id="station">Radio Skittles</div>
        <p class="meta" id="dj">Waiting for the signal.</p>
      </div>
    </section>
    <section class="stack">
      <div style="text-align:center"><div class="onair" id="status">Idle</div></div>
      <div class="panel">
        <div class="label">Why this song?</div>
        <p class="meta" id="pickReason">Chosen from your local library based on freshness, artist spacing, and station flow.</p>
      </div>
      <div class="panel">
        <div class="label">Up Next</div>
        <div class="queue" id="queue"></div>
      </div>
    </section>
  </main>
  <footer>
    <span>Listen</span>
    <span>Relax</span>
    <span>Request a song: /request</span>
  </footer>
  <script>
    const bars = document.getElementById('bars');
    for (let i = 0; i < 44; i++) {
      const bar = document.createElement('div');
      bar.className = 'bar';
      bar.style.setProperty('--h', `${'$'}{40 + Math.round(Math.random() * 260)}px`);
      bar.style.animationDelay = `${'$'}{Math.random() * -1.4}s`;
      bars.appendChild(bar);
    }

    let state = null;
    async function refresh() {
      try {
        const response = await fetch('/api/state', { cache: 'no-store' });
        state = await response.json();
        render();
      } catch (error) {
        document.getElementById('status').textContent = 'Offline';
      }
    }

    function render() {
      const track = state.nowPlaying;
      document.getElementById('station').textContent = state.stationName;
      document.getElementById('status').textContent = state.status;
      document.getElementById('vibe').textContent = state.vibe;
      document.getElementById('dj').textContent = state.djLine || 'Waiting for the signal.';
      document.getElementById('pickReason').textContent = track && track.pickReason ? track.pickReason : 'Chosen from your local library based on freshness, artist spacing, and station flow.';
      document.getElementById('title').textContent = track ? track.title : 'Waiting for signal';
      document.getElementById('artist').textContent = track ? track.artist : 'Start the bot show';
      const cover = document.getElementById('cover');
      const coverImage = document.getElementById('coverImage');
      const coverText = document.getElementById('coverText');
      coverText.textContent = track ? initials(track.artist) : 'RADIO SKITTLES';
      if (track && track.coverUrl) {
        coverImage.onload = () => cover.classList.add('has-art');
        coverImage.onerror = () => {
          cover.classList.remove('has-art');
          coverImage.removeAttribute('src');
        };
        if (coverImage.getAttribute('src') !== track.coverUrl) {
          cover.classList.remove('has-art');
          coverImage.src = track.coverUrl;
        }
      } else {
        cover.classList.remove('has-art');
        coverImage.removeAttribute('src');
      }
      document.getElementById('queue').innerHTML = state.upNext.length
        ? state.upNext.map(item => `<div class="queue-item"><div>${'$'}{escapeHtml(item.title)}<div class="small">${'$'}{escapeHtml(item.artist)}</div></div><div class="small">${'$'}{formatTime(item.durationMs)}</div></div>`).join('')
        : '<p class="meta">No tracks queued.</p>';
      updateProgress();
    }

    function updateProgress() {
      if (!state || !state.progressStartedAtEpochMs || !state.progressDurationMs) {
        document.getElementById('progress').style.width = '0%';
        document.getElementById('elapsed').textContent = '0:00';
        document.getElementById('duration').textContent = state && state.nowPlaying ? formatTime(state.nowPlaying.durationMs) : '0:00';
        return;
      }
      const elapsed = Date.now() - state.progressStartedAtEpochMs;
      const boundedElapsed = Math.max(0, Math.min(elapsed, state.progressDurationMs));
      const pct = Math.max(0, Math.min(100, elapsed / state.progressDurationMs * 100));
      document.getElementById('progress').style.width = `${'$'}{pct}%`;
      document.getElementById('elapsed').textContent = formatTime(boundedElapsed);
      document.getElementById('duration').textContent = formatTime(state.progressDurationMs);
    }

    function initials(value) {
      return value.split(/\s+/).filter(Boolean).slice(0, 2).map(word => word[0]).join('').toUpperCase();
    }

    function formatTime(ms) {
      const seconds = Math.round((ms || 180000) / 1000);
      return `${'$'}{Math.floor(seconds / 60)}:${'$'}{String(seconds % 60).padStart(2, '0')}`;
    }

    function escapeHtml(value) {
      return String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
    }

    refresh();
    setInterval(refresh, 1500);
    setInterval(updateProgress, 500);
  </script>
</body>
</html>
""".trimIndent()
