package dev.snapbudget.parsing

import java.math.BigDecimal

/** Shared amount validation, independent of any provider parser. */
object ReceiptAmountValidator {
    private val numericAmount = Regex("""(?:\d+|\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})(?:\.\d{1,2})?""")

    fun parseAmount(value: String): Long? = runCatching {
        val clean = value.trim()
        require(numericAmount.matches(clean))
        BigDecimal(clean.replace(",", "")).movePointRight(2).longValueExact().also { require(it > 0) }
    }.getOrNull()
}
