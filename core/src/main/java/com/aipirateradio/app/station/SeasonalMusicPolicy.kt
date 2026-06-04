package com.aipirateradio.app.station

import java.time.Instant
import java.time.Month
import java.time.ZoneId

object SeasonalMusicPolicy {
    fun rejectionReason(song: Song, now: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()): String? {
        if (!song.looksHolidaySeasonal()) return null
        return if (isHolidaySeason(now, zoneId)) null else "Holiday song outside seasonal window"
    }

    fun isHolidaySeason(now: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val date = now.atZone(zoneId).toLocalDate()
        return date.month == Month.DECEMBER || (date.month == Month.NOVEMBER && date.dayOfMonth >= 20)
    }

    private fun Song.looksHolidaySeasonal(): Boolean {
        val searchable = listOfNotNull(title, album, artist)
            .plus(genreTags)
            .plus(moodTags)
            .joinToString(" ")
            .lowercase()
        return HOLIDAY_TERMS.any { term -> searchable.contains(term) }
    }

    private val HOLIDAY_TERMS = listOf(
        "christmas",
        "xmas",
        "x-mas",
        "x-m@$",
        "santa",
        "jingle",
        "noel",
        "mistletoe",
        "yuletide",
        "deck the halls",
        "silent night",
        "holy night",
        "winter wonderland",
        "let it snow",
        "little drummer boy"
    )
}
