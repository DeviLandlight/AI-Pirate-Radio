# AI Pirate Radio Discord Bot

This is a separate desktop Discord bot module. It reuses the shared `core` radio logic without replacing the Android app.

## Run

Set these environment variables:

```powershell
$env:DISCORD_TOKEN="your bot token"
$env:MUSIC_LIBRARY_PATH="C:\Path\To\Music"
$env:BOT_DATA_PATH="bot-data"
$env:BOT_HISTORY_LIMIT="240"
$env:LASTFM_API_KEY="optional, for planning shows beyond your local files"
$env:OPENAI_API_KEY="optional"
```

Install `ffmpeg` and make sure it is available on `PATH`; the bot uses it to stream local audio files into Discord as 48kHz stereo PCM.

Then run:

```powershell
.\gradlew.bat :bot:run
```

## Commands

- `/join` joins your current voice channel.
- `/prepare` prepares a radio show from your local music folder or the sample catalog.
- `/queue` shows the prepared show.
- `/refresh` matches the prepared show against files you downloaded after preparing.
- `/play` plays the prepared show in voice.
- `/status` checks whether the bot can see your music folder and `ffmpeg`.
- `/stop` stops playback and leaves voice.

Prepared shows and play history are saved under `BOT_DATA_PATH`, defaulting to `bot-data` in the project folder. This folder is ignored by git because it can contain personal listening history.

`BOT_HISTORY_LIMIT` controls how many planned/played songs are remembered per server voice channel. The station planner uses that saved history to avoid recent song and artist repeats.
