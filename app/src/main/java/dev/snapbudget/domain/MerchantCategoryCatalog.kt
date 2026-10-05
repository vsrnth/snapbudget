package dev.snapbudget.domain

import java.util.Locale

/** Merchant identity policy shared by the in-memory and persisted mappings. */
object MerchantCategoryCatalog {
    fun key(merchant: String): String = merchant.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun validationError(merchant: String): String? {
        val normalized = key(merchant)
        return when {
            merchant.any { it.isISOControl() } -> "Merchant names cannot contain control characters."
            normalized.isEmpty() -> "Enter a merchant name."
            normalized.length > MAX_MERCHANT_LENGTH -> "Merchant names must be 120 characters or fewer."
            else -> null
        }
    }

    const val MAX_MERCHANT_LENGTH = 120
}
