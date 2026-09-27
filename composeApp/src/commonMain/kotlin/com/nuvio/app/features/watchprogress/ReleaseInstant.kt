package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import com.nuvio.app.features.watching.domain.isoCalendarDateOrNull

/**
 * A `released` string interpreted once, so every consumer agrees on what it means.
 *
 * Addons hand us two shapes for the same airing and they disagree by a calendar day:
 *  - TMDB `air_date` — `2026-09-03`, the date in the *network's* timezone, no time of day.
 *  - TVDB/Cinemeta — `2026-09-04T01:00:00.000Z`, the same 9pm-ET drop as a UTC instant.
 *  - AIOMetadata with no known air time — `2026-09-17T12:00:00.000Z`, a bare date dressed up as
 *    an instant (see [NoonUtcSentinel]); treated like the first form.
 *
 * The old code took `substringBefore('T')` of the raw string, which made the second form land on
 * Sept 4 for every viewer on earth regardless of their timezone, and pinned the first form to UTC
 * midnight. Both are resolved here to a real instant and to the date it falls on *locally*.
 */
data class ReleaseInstant(
    val epochMs: Long,
    /** The calendar date this airing falls on in the viewer's timezone. */
    val localIsoDate: String,
    /** False when the source only gave us a date, so the instant is that day's local midnight. */
    val hasTimeOfDay: Boolean,
)

/**
 * AIOMetadata's "air time unknown" anchor. Its TVDB path builds `released` from the episode date
 * plus the series' `airsTime` in the origin country's timezone, and when it cannot resolve that
 * timezone it pins the bare date to noon UTC instead (`getMeta.js`, `resolveReleaseTimestamp`).
 * That happens for every UK show, because TVDB reports the country as `gbr` and the addon's
 * timezone table only knows `gb`/`uk` — so "All Creatures Great & Small" S7E1 arrived as
 * `2026-09-17T12:00:00.000Z` (8am ET) for a 9pm BST slot, and the Up Next card counted down to a
 * time eight hours early. Noon UTC is a placeholder, not a broadcast, so it is read as date-only.
 */
private val NoonUtcSentinel = Regex("""T12:00:00(?:\.0+)?Z$""")

/** True for the noon-UTC placeholder AIOMetadata emits when it could not resolve an air time. */
fun isPlaceholderAirTime(released: String?): Boolean =
    released != null && NoonUtcSentinel.containsMatchIn(released.trim())

/**
 * Rebuilds a real air instant for a placeholder-dated episode from what TVDB knows about the
 * series — the same date + `airsTime` + origin-country-timezone recipe the addon uses when it
 * succeeds — so the Up Next countdown lands on the broadcast slot rather than on noon UTC (or,
 * with the placeholder read as date-only, on local midnight, which fires "New Season" the moment
 * the calendar day begins). Null when the schedule has no usable time or country, in which case
 * the placeholder stays and is treated as date-only.
 */
fun repairPlaceholderAirTime(released: String?, airsTime: String?, originCountry: String?): String? {
    if (!isPlaceholderAirTime(released)) return null
    val date = isoCalendarDateOrNull(released) ?: return null
    val (hour, minute) = parseAirsTime(airsTime) ?: return null
    val zoneId = airTimeZoneForCountry(originCountry) ?: return null
    return CurrentDateProvider.zonedWallClockIsoUtc(date, hour, minute, zoneId)
}

/** TVDB `airsTime`: `21:00`, `9:00 PM`, `9:00pm`. */
internal fun parseAirsTime(raw: String?): Pair<Int, Int>? {
    val text = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    val match = Regex("""^(\d{1,2}):(\d{2})\s*([AaPp])?\.?[Mm]?\.?$""").find(text) ?: return null
    var hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    val meridiem = match.groupValues[3].lowercase()
    if (meridiem.isNotEmpty()) {
        if (hour !in 1..12) return null
        if (hour == 12) hour = 0
        if (meridiem == "p") hour += 12
    }
    return (hour to minute).takeIf { hour in 0..23 && minute in 0..59 }
}

/**
 * The timezone a country's broadcast schedule is written in, for the countries TVDB series most
 * often originate from. Both ISO 3166-1 alpha-2 and alpha-3 spellings, since TVDB returns the
 * latter (`gbr`) and that is exactly the spelling the addon's own table was missing. Countries
 * spanning several zones use the one their national networks schedule against.
 */
internal fun airTimeZoneForCountry(country: String?): String? {
    val key = country?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    return AirTimeZonesByCountry[key]
}

private val AirTimeZonesByCountry: Map<String, String> = buildMap {
    fun zone(zone: String, vararg codes: String) = codes.forEach { put(it, zone) }
    zone("America/New_York", "us", "usa")
    zone("Europe/London", "gb", "gbr", "uk")
    zone("America/Toronto", "ca", "can")
    zone("Australia/Sydney", "au", "aus")
    zone("Pacific/Auckland", "nz", "nzl")
    zone("Europe/Dublin", "ie", "irl")
    zone("Europe/Berlin", "de", "deu", "ger")
    zone("Europe/Paris", "fr", "fra")
    zone("Europe/Madrid", "es", "esp")
    zone("Europe/Rome", "it", "ita")
    zone("Europe/Amsterdam", "nl", "nld")
    zone("Europe/Brussels", "be", "bel")
    zone("Europe/Zurich", "ch", "che")
    zone("Europe/Vienna", "at", "aut")
    zone("Europe/Lisbon", "pt", "prt")
    zone("Europe/Stockholm", "se", "swe")
    zone("Europe/Oslo", "no", "nor")
    zone("Europe/Copenhagen", "dk", "dnk")
    zone("Europe/Helsinki", "fi", "fin")
    zone("Europe/Warsaw", "pl", "pol")
    zone("Europe/Prague", "cz", "cze")
    zone("Europe/Budapest", "hu", "hun")
    zone("Europe/Bucharest", "ro", "rou")
    zone("Europe/Athens", "gr", "grc")
    zone("Europe/Istanbul", "tr", "tur")
    zone("Europe/Moscow", "ru", "rus")
    zone("Asia/Tokyo", "jp", "jpn")
    zone("Asia/Seoul", "kr", "kor")
    zone("Asia/Shanghai", "cn", "chn")
    zone("Asia/Taipei", "tw", "twn")
    zone("Asia/Hong_Kong", "hk", "hkg")
    zone("Asia/Kolkata", "in", "ind")
    zone("Asia/Bangkok", "th", "tha")
    zone("Asia/Manila", "ph", "phl")
    zone("Asia/Ho_Chi_Minh", "vn", "vnm")
    zone("Asia/Jakarta", "id", "idn")
    zone("Asia/Singapore", "sg", "sgp")
    zone("Asia/Dubai", "ae", "are")
    zone("Asia/Jerusalem", "il", "isr")
    zone("Africa/Cairo", "eg", "egy")
    zone("Africa/Johannesburg", "za", "zaf")
    zone("America/Mexico_City", "mx", "mex")
    zone("America/Sao_Paulo", "br", "bra")
    zone("America/Argentina/Buenos_Aires", "ar", "arg")
    zone("America/Bogota", "co", "col")
    zone("America/Santiago", "cl", "chl")
}

fun resolveReleaseInstant(raw: String?): ReleaseInstant? {
    val trimmed = raw?.trim()?.takeIf(String::isNotBlank) ?: return null

    val exactEpochMs = if (NoonUtcSentinel.containsMatchIn(trimmed)) null else parseTraktIsoDateTimeToEpochMs(trimmed)
    if (exactEpochMs != null) {
        return ReleaseInstant(
            epochMs = exactEpochMs,
            localIsoDate = CurrentDateProvider.localIsoDateAt(exactEpochMs),
            hasTimeOfDay = true,
        )
    }

    // For the noon-UTC sentinel this is the date before the 'T' — the network's calendar date,
    // which is exactly what the addon started from.
    val datePart = isoCalendarDateOrNull(trimmed) ?: return null
    val startOfDayMs = CurrentDateProvider.startOfLocalDayEpochMs(datePart) ?: return null
    return ReleaseInstant(
        epochMs = startOfDayMs,
        localIsoDate = datePart,
        hasTimeOfDay = false,
    )
}

/**
 * The calendar date an airing lands on in the viewer's timezone, or null if the string is not a
 * date at all (a bare year, an open-ended `2026-` range).
 *
 * This is the one conversion every "has it aired / how long until it airs" check should use.
 */
fun localReleaseDateOrNull(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf(String::isNotBlank) ?: return null

    // A value with no time of day is already a plain calendar date, so it needs no conversion —
    // and this runs over whole catalogs, so it should not pay for a timezone lookup per item.
    // It also keeps the old string behaviour for impossible dates like 2026-02-31, which have no
    // resolvable instant but were previously still compared as dates.
    if (!trimmed.contains('T')) {
        return isoCalendarDateOrNull(trimmed)
    }
    return resolveReleaseInstant(trimmed)?.localIsoDate
}

/**
 * Picks between the addon's own `released` and TMDB's `air_date` for the same episode.
 *
 * TMDB enrichment used to overwrite unconditionally, so the countdown flipped by a whole day
 * depending on whether the TMDB request happened to time out — the timestamped value survived on
 * failure and was replaced by the date-only one on success. When both describe the same airing the
 * timestamped one wins, because it is the only one that knows what time of day the episode drops.
 *
 * "Same airing" is a ±1 day window: the two forms are expected to disagree by a day (that is the
 * whole point), but a TMDB date further out than that is a genuine schedule correction and should
 * replace the addon's stale timestamp.
 */
fun preferPreciseReleaseDate(addonReleased: String?, tmdbAirDate: String?): String? {
    val tmdb = tmdbAirDate?.trim()?.takeIf(String::isNotBlank) ?: return addonReleased
    val addonInstant = resolveReleaseInstant(addonReleased)?.takeIf { it.hasTimeOfDay }
        ?: return tmdb

    val tmdbDate = isoCalendarDateOrNull(tmdb) ?: return addonReleased
    val driftDays = isoDaysBetween(from = addonInstant.localIsoDate, to = tmdbDate) ?: return tmdb
    return if (driftDays in -1..1) addonReleased else tmdb
}

/** Whole calendar days from [from] to [to], both `yyyy-MM-dd` in the same timezone. */
internal fun isoDaysBetween(from: String, to: String): Int? {
    val start = isoCalendarDateOrNull(from) ?: return null
    val end = isoCalendarDateOrNull(to) ?: return null
    return (com.nuvio.app.features.watching.domain.isoEpochDay(end) -
        com.nuvio.app.features.watching.domain.isoEpochDay(start)).toInt()
}
