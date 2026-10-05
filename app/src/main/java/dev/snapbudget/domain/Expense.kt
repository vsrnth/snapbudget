package dev.snapbudget.domain

import java.math.BigInteger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID

private const val MAX_MERCHANT_LENGTH = 120
private const val MAX_CATEGORY_LENGTH = 60
private val strictDateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm")
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

/** A confirmed-storage record represented without Android, Room, or receipt-parser types. */
data class ExpenseRecord(
    val id: Long,
    val amountPaise: Long,
    val merchant: String,
    val dateTime: LocalDateTime,
    val category: String,
)

/** Validated manual-entry fields and the stable operation identity for this draft and its retries. */
class ManualExpenseDraft internal constructor(
    val amountPaise: Long,
    val merchant: String,
    val dateTime: LocalDateTime,
    val category: String,
    val operationToken: String,
) {
    companion object {
        fun create(
            amount: String,
            merchant: String,
            dateTime: String,
            category: String,
        ): ManualExpenseDraft? = createWithToken(amount, merchant, dateTime, category, "manual:${UUID.randomUUID()}")

        /** Creates a draft with a supplied token, primarily for deterministic callers and tests. */
        fun createWithToken(
            amount: String,
            merchant: String,
            dateTime: String,
            category: String,
            operationToken: String,
        ): ManualExpenseDraft? {
            val paise = parsePositivePaise(amount) ?: return null
            val trimmedMerchant = merchant.trim()
            if (trimmedMerchant.isEmpty() || trimmedMerchant.length > MAX_MERCHANT_LENGTH) return null
            val trimmedCategory = category.trim()
            if (trimmedCategory.isEmpty() || trimmedCategory.length > MAX_CATEGORY_LENGTH) return null
            if (!operationToken.startsWith("manual:") || operationToken.length <= "manual:".length) return null
            val parsedDateTime = try {
                LocalDateTime.parse(dateTime, strictDateTimeFormatter)
            } catch (_: RuntimeException) {
                return null
            }
            return ManualExpenseDraft(paise, trimmedMerchant, parsedDateTime, trimmedCategory, operationToken)
        }
    }
}

/** Parses a positive decimal rupee amount exactly; grouping separators and floating point are rejected. */
fun parsePositivePaise(value: String): Long? {
    return try {
        if (!Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?").matches(value)) return null
        val parts = value.split('.')
        val rupees = BigInteger(parts[0])
        val paise = if (parts.size == 1) BigInteger.ZERO else BigInteger(parts[1].padEnd(2, '0'))
        rupees.multiply(BigInteger.valueOf(100)).add(paise)
            .takeIf { it.signum() > 0 && it <= BigInteger.valueOf(Long.MAX_VALUE) }
            ?.longValueExact()
    } catch (_: RuntimeException) {
        null
    }
}

/** Calculates an unbounded exact total in paise, including collections exceeding Long capacity. */
fun totalPaise(expenses: Iterable<ExpenseRecord>): BigInteger =
    expenses.fold(BigInteger.ZERO) { total, expense -> total + BigInteger.valueOf(expense.amountPaise) }

/** Formats paise deterministically with Indian digit grouping and exactly two decimals. */
fun formatInr(amountPaise: BigInteger): String {
    val negative = amountPaise.signum() < 0
    val absolute = amountPaise.abs()
    val rupees = absolute.divide(BigInteger.valueOf(100)).toString()
    val lastThreeStart = (rupees.length - 3).coerceAtLeast(0)
    val grouped = if (lastThreeStart == 0) rupees else {
        val prefix = rupees.substring(0, lastThreeStart)
        val firstGroupLength = if (prefix.length % 2 == 0) 2 else 1
        buildString {
            append(prefix.substring(0, firstGroupLength))
            var index = firstGroupLength
            while (index < prefix.length) {
                append(',').append(prefix.substring(index, index + 2))
                index += 2
            }
            append(',').append(rupees.substring(lastThreeStart))
        }
    }
    val paise = absolute.remainder(BigInteger.valueOf(100)).toString().padStart(2, '0')
    return "${if (negative) "-" else ""}₹$grouped.$paise"
}

/** Formats a Long paise amount for the common storage-total case. */
fun formatInr(amountPaise: Long): String = formatInr(BigInteger.valueOf(amountPaise))
