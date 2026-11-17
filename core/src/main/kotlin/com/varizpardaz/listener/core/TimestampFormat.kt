package com.varizpardaz.listener.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * ISO-8601 with offset, whole seconds, always rendered at +03:30 (contract section "All timestamps").
 *
 * A fixed offset instead of the phone's zone keeps the string for one SMS identical between the live
 * path and a later inbox rescan, whatever the phone's zone or tzdata says, and the server's dedupe key
 * includes `sc_timestamp`. Iran has not used DST since 2022; the offset is only notation, the instant
 * is always exact.
 */
object TimestampFormat {
    val OFFSET: ZoneOffset = ZoneOffset.ofHoursMinutes(3, 30)

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    fun format(epochMillis: Long): String =
        formatter.format(Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).atOffset(OFFSET))

    fun parseToEpochMillis(text: String): Long? =
        try {
            java.time.OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
}
