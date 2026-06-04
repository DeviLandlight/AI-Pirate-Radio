# AI Pirate Radio Discord Bot

This is a separate desktop Discord bot module. It reuses the shared `core` radio logic without replacing the Android app.

## Run

Set these environment variables:

```powershell
$env:DISCORD_TOKEN="your bot token"
$env:MUSIC_LIBRARY_PATH="C:\Path\To\Music"
$env:BOT_DATA_PATH="bot-data"
$env:BOT_HISTORY_LIMIT="240"
$env:VISUALIZER_PORT="8787"
$env:YTDLP_PATH="yt-dlp"
$env:DJ_VOICE="optional installed Windows voice name"
$env:DJ_VOICE_RATE="1"
$env:DJ_VOICE_VOLUME="100"
$env:DJ_TTS_PROVIDER="windows"
$env:OPENAI_TTS_MODEL="gpt-4o-mini-tts"
$env:OPENAI_TTS_VOICE="coral"
$env:LASTFM_API_KEY="optional, for planning shows beyond your local files"
$env:OPENAI_API_KEY="optional"
```

Install `ffmpeg` and make sure it is available on `PATH`; the bot uses it to stream local audio files into Discord as 48kHz stereo PCM. Install `yt-dlp` if you want the bot to download missing tracks for prepared shows.

Then run:

```powershell
.\gradlew.bat :bot:run
```

## Commands

- `/join` joins your current voice channel.
- `/prepare` prepares a radio show from your local music folder or the sample catalog.
- `/prepare-next` appends another radio block after the current queue so playback can continue without stopping.
- `/prepare-journey` prepares a beat-by-beat radio journey where the DJ connects the songs into an arc.
- `/prepare-local` prepares a radio show using only files already in your music folder.
- `/vibes` lists built-in vibe names/ids and artist seeds for `/prepare`.
- `/queue` shows the prepared show.
- `/request` adds a listener request to the current prepared show. It accepts `Artist - Song`, `Song by Artist`, or just an artist name so the station can pick a fitting song.
- `/ask` queues a call-in question for the DJ to answer over voice between songs.
- `/refresh` matches the prepared show against files you downloaded after preparing.
- `/download-missing` uses `yt-dlp` to download missing prepared tracks into `MUSIC_LIBRARY_PATH`.
- `/play` plays the prepared show in voice.
- `/clear-history` clears saved song/artist history for your current voice channel.
- `/test-dj` speaks a short DJ test line in your current voice channel.
- `/voices` lists installed Windows voices for DJ speech.
- `/status` checks whether the bot can see your music folder and `ffmpeg`.
- `/pause` stops playback and saves the next track so `/play` can resume later.
- `/stop` stops playback and leaves voice.

Prepared shows and play history are saved under `BOT_DATA_PATH`, defaulting to `bot-data` in the project folder. This folder is ignored by git because it can contain personal listening history.

`BOT_HISTORY_LIMIT` controls how many planned/played songs are remembered per server voice channel. The station planner uses that saved history to avoid recent song and artist repeats.

`/download-missing` searches YouTube via `yt-dlp` and extracts audio to mp3. Only use it for content you have rights to download.

`/request` allows one accepted request per user every 20 minutes, rejects exact song repeats from the current queue/recent history, and uses the OpenAI text model to reject only clearly off-vibe requests. If you request only an artist, the bot tries your local library first, then Last.fm, and asks you to run `/download-missing` if the picked song is not local yet.

When a `/prepare-journey` show is still inside its protected journey block, accepted requests are saved after the journey instead of being inserted into the middle of the arc.

`/ask` allows one call-in question per user every 15 minutes. The DJ answers queued questions between songs, using the current set as the frame even when the question is not strictly about music.

Set `DJ_TTS_PROVIDER=openai` to use OpenAI speech for DJ segues. It requires `OPENAI_API_KEY`. `OPENAI_TTS_VOICE` can be changed, for example to `coral`, `verse`, or `alloy`.

The bot also starts a local visualizer page by default at `http://localhost:8787`. Set `VISUALIZER_PORT=0` to disable it, or set another port if `8787` is already in use.
