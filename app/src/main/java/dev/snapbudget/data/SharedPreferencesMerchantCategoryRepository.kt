package dev.snapbudget.data

import android.content.Context
import dev.snapbudget.domain.CategoryCatalog
import dev.snapbudget.domain.MerchantCategoryCatalog
import dev.snapbudget.domain.MerchantCategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stores local merchant-category associations in app-private preferences. */
class SharedPreferencesMerchantCategoryRepository(
    context: Context,
    preferenceFileName: String = PREFERENCES,
) : MerchantCategoryRepository {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceFileName, Context.MODE_PRIVATE)

    override suspend fun loadMappings(): Map<String, String> = withContext(Dispatchers.IO) {
        synchronized(preferences) { readMappings() }
    }

    override suspend fun rememberCategory(merchant: String, category: String): Map<String, String> = withContext(Dispatchers.IO) {
        require(MerchantCategoryCatalog.validationError(merchant) == null) { "Invalid merchant name" }
        require(CategoryCatalog.validationError(category) == null) { "Invalid category name" }
        synchronized(preferences) {
            val updated = readMappings().toMutableMap()
            updated[MerchantCategoryCatalog.key(merchant)] = CategoryCatalog.normalizeName(category)
            check(preferences.edit().putStringSet(KEY, encode(updated)).commit()) { "Merchant category preferences could not be saved" }
            updated.toMap()
        }
    }

    private fun readMappings(): Map<String, String> {
        if (!preferences.contains(KEY)) return emptyMap()
        val stored = preferences.all[KEY] as? Set<*> ?: error("Merchant category preferences are unreadable")
        if (stored.any { it !is String }) error("Merchant category preferences are unreadable")
        val result = linkedMapOf<String, String>()
        for (entry in stored.filterIsInstance<String>()) {
            val separator = entry.indexOf(DELIMITER)
            if (separator <= 0 || separator != entry.lastIndexOf(DELIMITER)) error("Merchant category preferences are unreadable")
            val merchantKey = entry.substring(0, separator)
            val category = entry.substring(separator + 1)
            if (merchantKey != MerchantCategoryCatalog.key(merchantKey) || MerchantCategoryCatalog.validationError(merchantKey) != null ||
                CategoryCatalog.validationError(category) != null || CategoryCatalog.normalizeName(category) != category ||
                result.put(merchantKey, category) != null
            ) error("Merchant category preferences are unreadable")
        }
        return result
    }

    private fun encode(mappings: Map<String, String>): Set<String> = mappings.mapTo(linkedSetOf()) { (merchant, category) -> "$merchant$DELIMITER$category" }

    companion object {
        private const val PREFERENCES = "snapbudget_merchant_categories"
        private const val KEY = "merchant_category_mappings"
        private const val DELIMITER = '\t'
    }
}
