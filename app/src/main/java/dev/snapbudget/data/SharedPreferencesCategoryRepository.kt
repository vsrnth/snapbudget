package dev.snapbudget.data

import android.content.Context
import dev.snapbudget.domain.CategoryCatalog
import dev.snapbudget.domain.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stores only custom category names in an app-private preferences file. */
class SharedPreferencesCategoryRepository(
    context: Context,
    preferenceFileName: String = PREFERENCES,
) : CategoryRepository {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceFileName, Context.MODE_PRIVATE)

    override suspend fun loadCustomCategories(): List<String> = withContext(Dispatchers.IO) {
        readCatalog()
    }

    override suspend fun addCustomCategory(name: String): List<String> = withContext(Dispatchers.IO) {
        require(CategoryCatalog.validationError(name) == null) { "Invalid category name" }
        synchronized(preferences) {
            val existing = readCatalog()
            val canonical = CategoryCatalog.existingName(name, CategoryCatalog.choices(existing, emptyList(), "")) ?: CategoryCatalog.normalizeName(name)
            val updated = if (CategoryCatalog.existingName(canonical, CategoryCatalog.predefined) != null || canonical in existing) existing else existing + canonical
            check(preferences.edit().putStringSet(KEY, updated.toSet()).commit()) { "Category preferences could not be saved" }
            updated
        }
    }

    private fun readCatalog(): List<String> {
        if (!preferences.contains(KEY)) return emptyList()
        val stored = preferences.all[KEY] as? Set<*> ?: error("Category preferences are unreadable")
        if (stored.any { it !is String }) error("Category preferences are unreadable")
        val names = stored.filterIsInstance<String>()
        check(names.all { CategoryCatalog.validationError(it) == null && CategoryCatalog.normalizeName(it) == it }) { "Category preferences are unreadable" }
        return names
    }

    companion object {
        private const val PREFERENCES = "snapbudget_categories"
        private const val KEY = "custom_categories"
    }
}
