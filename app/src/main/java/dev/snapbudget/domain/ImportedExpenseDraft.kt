package dev.snapbudget.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

private const val MAX_IMPORTED_MERCHANT_LENGTH = 120
private const val MAX_IMPORTED_CATEGORY_LENGTH = 60
private val importedDateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm")
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
private val transactionIdPattern = Regex("(?:T[0-9]{15,35}|GPAY:UPI:[0-9]{12}|GPAY:GOOGLE:[A-Za-z0-9_-]{6,128})")

/** User-correctable receipt fields bound to immutable original-image and transaction identities. */
class ImportedExpenseDraft private constructor(
    val amountPaise: Long,
    val merchant: String,
    val dateTime: LocalDateTime,
    val category: String,
    val imageHash: String,
    val transactionId: String?,
) {
    /** Applies user corrections while retaining the receipt's original-byte hash and transaction ID. */
    fun withCorrections(
        amountPaise: Long,
        merchant: String,
        dateTime: String,
        category: String,
    ): ImportedExpenseDraft? = create(amountPaise, merchant, dateTime, category, imageHash, transactionId)

    companion object {
        /** Returns null when a corrected field or either stable identity is invalid. */
        fun create(
            amountPaise: Long,
            merchant: String,
            dateTime: String,
            category: String,
            imageHash: String,
            transactionId: String? = null,
        ): ImportedExpenseDraft? {
            if (amountPaise <= 0L) return null
            val normalizedMerchant = merchant.trim()
            if (normalizedMerchant.isEmpty() || normalizedMerchant.length > MAX_IMPORTED_MERCHANT_LENGTH) return null
            val normalizedCategory = category.trim()
            if (normalizedCategory.isEmpty() || normalizedCategory.length > MAX_IMPORTED_CATEGORY_LENGTH) return null
            val parsedDateTime = try {
                LocalDateTime.parse(dateTime, importedDateTimeFormatter)
            } catch (_: RuntimeException) {
                return null
            }
            if (!sha256Pattern.matches(imageHash)) return null
            if (transactionId != null && !transactionIdPattern.matches(transactionId)) return null
            return ImportedExpenseDraft(
                amountPaise = amountPaise,
                merchant = normalizedMerchant,
                dateTime = parsedDateTime,
                category = normalizedCategory,
                imageHash = imageHash.lowercase(Locale.ROOT),
                transactionId = transactionId,
            )
        }
    }
}
