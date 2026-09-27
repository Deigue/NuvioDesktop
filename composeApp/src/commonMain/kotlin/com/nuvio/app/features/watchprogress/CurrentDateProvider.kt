package com.nuvio.app.features.watchprogress

expect object CurrentDateProvider {
    fun todayIsoDate(): String

    /** The calendar date [epochMs] falls on **in the viewer's timezone**, as `yyyy-MM-dd`. */
    fun localIsoDateAt(epochMs: Long): String

    /**
     * The first instant of [isoDate] in the viewer's timezone, or null if the date is not a real
     * calendar date (`isoCalendarDateOrNull` accepts day 31 in February, so this can be reached).
     */
    fun startOfLocalDayEpochMs(isoDate: String): Long?

    /**
     * The instant at which [hour]:[minute] on [isoDate] occurs in the IANA zone [zoneId]
     * (`Europe/London`), as an ISO-8601 UTC string like `2026-09-17T20:00:00Z`, or null if the
     * zone or date is not real. Daylight saving is the zone's, not the viewer's.
     */
    fun zonedWallClockIsoUtc(isoDate: String, hour: Int, minute: Int, zoneId: String): String?
}
