package dev.snapbudget.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

private const val MAX_EDIT_MERCHANT_LENGTH = 120
private const val MAX_EDIT_CATEGORY_LENGTH = 60
private val editDateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm")
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

/** Validated editable fields for an existing expense. Stable identity fields are deliberately absent. */
class ExpenseEditDraft internal constructor(
    val id: Long,
    val amountPaise: Long,
    val merchant: String,
    val dateTime: LocalDateTime,
    val category: String,
) {
    companion object {
        fun create(id: Long, amount: String, merchant: String, dateTime: String, category: String): ExpenseEditDraft? {
            if (id <= 0L) return null
            val paise = parsePositivePaise(amount) ?: return null
            val normalizedMerchant = merchant.trim()
            if (normalizedMerchant.isEmpty() || normalizedMerchant.length > MAX_EDIT_MERCHANT_LENGTH) return null
            val normalizedCategory = category.trim()
            if (normalizedCategory.isEmpty() || normalizedCategory.length > MAX_EDIT_CATEGORY_LENGTH) return null
            val parsedDateTime = try {
                LocalDateTime.parse(dateTime, editDateTimeFormatter)
            } catch (_: RuntimeException) {
                return null
            }
            if (parsedDateTime.format(editDateTimeFormatter) != dateTime) return null
            return ExpenseEditDraft(id, paise, normalizedMerchant, parsedDateTime, normalizedCategory)
        }
    }
}
