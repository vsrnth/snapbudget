package dev.snapbudget.domain

interface MerchantCategoryRepository {
    suspend fun loadMappings(): Map<String, String>

    /** Stores the latest category for a merchant and returns the complete normalized-key mapping. */
    suspend fun rememberCategory(merchant: String, category: String): Map<String, String>
}

/** Backward-compatible repository for ViewModels created without production wiring. */
class InMemoryMerchantCategoryRepository : MerchantCategoryRepository {
    private val mappings = linkedMapOf<String, String>()

    override suspend fun loadMappings(): Map<String, String> = synchronized(mappings) { mappings.toMap() }

    override suspend fun rememberCategory(merchant: String, category: String): Map<String, String> {
        require(MerchantCategoryCatalog.validationError(merchant) == null) { "Invalid merchant name" }
        require(CategoryCatalog.validationError(category) == null) { "Invalid category name" }
        synchronized(mappings) {
            mappings[MerchantCategoryCatalog.key(merchant)] = CategoryCatalog.normalizeName(category)
            return mappings.toMap()
        }
    }
}
