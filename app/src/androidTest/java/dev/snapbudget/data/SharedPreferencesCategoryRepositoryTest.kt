package dev.snapbudget.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SharedPreferencesCategoryRepositoryTest {
    @Test fun customChoicesSurviveAdapterRecreationInIsolatedPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "category-fixture-${UUID.randomUUID()}"
        try {
            val first = SharedPreferencesCategoryRepository(context, file)
            assertEquals(emptyList<String>(), first.loadCustomCategories())
            assertEquals(listOf("Coffee"), first.addCustomCategory("  Coffee  "))
            assertEquals(listOf("Coffee"), first.addCustomCategory("coffee"))
            assertEquals(listOf("Coffee"), SharedPreferencesCategoryRepository(context, file).loadCustomCategories())
        } finally {
            context.deleteSharedPreferences(file)
        }
    }

    @Test fun invalidNameDoesNotMutateAnOtherwiseEmptyCatalog() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "category-invalid-${UUID.randomUUID()}"
        try {
            val repository = SharedPreferencesCategoryRepository(context, file)
            val result = runCatching { repository.addCustomCategory("Bad\nName") }
            assertTrue(result.isFailure)
            assertEquals(emptyList<String>(), repository.loadCustomCategories())
        } finally {
            context.deleteSharedPreferences(file)
        }
    }

    @Test fun corruptCatalogIsReportedAndPreservedWhenAddIsAttempted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "category-corrupt-${UUID.randomUUID()}"
        try {
            val preferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            assertTrue(preferences.edit().putString("custom_categories", "synthetic-corrupt-data").commit())
            val repository = SharedPreferencesCategoryRepository(context, file)
            assertTrue(runCatching { repository.loadCustomCategories() }.isFailure)
            assertTrue(runCatching { repository.addCustomCategory("Coffee") }.isFailure)
            assertEquals("synthetic-corrupt-data", preferences.getString("custom_categories", null))
        } finally {
            context.deleteSharedPreferences(file)
        }
    }
}
