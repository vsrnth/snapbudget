package dev.snapbudget.domain

interface CategoryRepository {
    suspend fun loadCustomCategories(): List<String>
    suspend fun addCustomCategory(name: String): List<String>
}

/** Suitable as a backward-compatible default for ViewModels created outside production wiring. */
class InMemoryCategoryRepository : CategoryRepository {
    private var categories: List<String> = emptyList()
    override suspend fun loadCustomCategories(): List<String> = categories
    override suspend fun addCustomCategory(name: String): List<String> {
        val existing = CategoryCatalog.existingName(name, CategoryCatalog.choices(categories, emptyList(), ""))
        if (existing == null) categories = categories + CategoryCatalog.normalizeName(name)
        return categories
    }
}
