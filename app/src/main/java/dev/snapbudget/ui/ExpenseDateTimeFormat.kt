package dev.snapbudget.ui

import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

/** Converts the stable, minute-precision local timestamp to and from user-facing text. */
object ExpenseDateTimeFormat {
    const val EXAMPLE = "3 May 2025, 9:41 PM"

    private val canonicalFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    private val readableFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM uuuu, h:mm a")
        .toFormatter(Locale.ENGLISH)
        .withResolverStyle(ResolverStyle.STRICT)

    fun format(dateTime: LocalDateTime): String = dateTime.format(readableFormatter)

    /** Returns readable text for a canonical timestamp, preserving unrecognized text for editing. */
    fun canonicalToReadable(value: String): String = parseCanonical(value)?.let(::format) ?: value

    /** Accepts readable input and legacy canonical input; incomplete or invalid input is retained. */
    fun inputToCanonical(value: String): String? {
        parseReadable(value)?.let { return it.format(canonicalFormatter) }
        parseCanonical(value)?.let { return it.format(canonicalFormatter) }
        return null
    }

    fun isValidInput(value: String): Boolean = parseReadable(value) != null || parseCanonical(value) != null

    private fun parseReadable(value: String): LocalDateTime? = runCatching {
        LocalDateTime.parse(value, readableFormatter)
    }.getOrNull()

    private fun parseCanonical(value: String): LocalDateTime? = runCatching {
        LocalDateTime.parse(value, canonicalFormatter)
    }.getOrNull()
}
