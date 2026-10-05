package dev.snapbudget.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SharedPreferencesMerchantCategoryRepositoryTest {
    @Test fun mappingsSurviveReopenAndEquivalentMerchantNamesOverrideTheAssociation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "merchant-category-${UUID.randomUUID()}"
        try {
            val first = SharedPreferencesMerchantCategoryRepository(context, file)
            assertEquals(emptyMap<String, String>(), first.loadMappings())
            assertEquals(mapOf("acme cafe" to "Food"), first.rememberCategory("  ACME   Cafe ", "Food"))
            assertEquals(mapOf("acme cafe" to "Groceries"), first.rememberCategory("Acme  Cafe", "Groceries"))
            assertEquals(mapOf("acme cafe" to "Groceries"), SharedPreferencesMerchantCategoryRepository(context, file).loadMappings())
        } finally {
            context.deleteSharedPreferences(file)
        }
    }

    @Test fun customCategoryNameIsAcceptedAndPreserved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "merchant-custom-category-${UUID.randomUUID()}"
        try {
            val repository = SharedPreferencesMerchantCategoryRepository(context, file)
            assertEquals(mapOf("corner shop" to "My custom label"), repository.rememberCategory("Corner Shop", " My custom label "))
            assertEquals(mapOf("corner shop" to "My custom label"), SharedPreferencesMerchantCategoryRepository(context, file).loadMappings())
        } finally {
            context.deleteSharedPreferences(file)
        }
    }

    @Test fun invalidAssociationDoesNotModifyStoredMappings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "merchant-invalid-${UUID.randomUUID()}"
        try {
            val repository = SharedPreferencesMerchantCategoryRepository(context, file)
            repository.rememberCategory("Corner Shop", "Food")
            assertTrue(runCatching { repository.rememberCategory("Bad\nName", "Bills") }.isFailure)
            assertTrue(runCatching { repository.rememberCategory("x".repeat(121), "Bills") }.isFailure)
            assertTrue(runCatching { repository.rememberCategory("Corner Shop", "Bad\nCategory") }.isFailure)
            assertEquals(mapOf("corner shop" to "Food"), repository.loadMappings())
        } finally {
            context.deleteSharedPreferences(file)
        }
    }

    @Test fun corruptMappingsFailClosedAndRemainUntouched() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "merchant-corrupt-${UUID.randomUUID()}"
        try {
            val preferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val malformed = setOf("missing-separator", "unsafe\tBad\nCategory")
            assertTrue(preferences.edit().putStringSet("merchant_category_mappings", malformed).commit())
            val repository = SharedPreferencesMerchantCategoryRepository(context, file)
            assertTrue(runCatching { repository.loadMappings() }.isFailure)
            assertTrue(runCatching { repository.rememberCategory("Corner Shop", "Food") }.isFailure)
            assertEquals(malformed, preferences.getStringSet("merchant_category_mappings", emptySet()))
        } finally {
            context.deleteSharedPreferences(file)
        }
    }
}
