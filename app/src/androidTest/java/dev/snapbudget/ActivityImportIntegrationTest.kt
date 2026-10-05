package dev.snapbudget

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.snapbudget.ui.ExpensePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityImportIntegrationTest {
    @Test
    fun pickerContractRequestsOpenableLocalImageDocumentsOnly() {
        val intent = OpenLocalImageDocument().createIntent(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            Unit,
        )
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("image/*", intent.type)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertEquals(true, intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0)
        assertEquals(null, intent.getStringExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test
    fun lifecycleFactoryRetainsReviewedDraftAcrossHostRecreation() {
        ActivityScenario.launch(FixtureSavedStateActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.expenseViewModel.updateMerchant("Fixture shop")
                activity.expenseViewModel.updateAmount("12.34")
                activity.expenseViewModel.updateDateTime("2025-01-02T03:04")
                activity.expenseViewModel.updateCategory("Food")
                activity.expenseViewModel.review()
                assertEquals(ExpensePage.REVIEW, activity.expenseViewModel.state.value.page)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(ExpensePage.REVIEW, activity.expenseViewModel.state.value.page)
                assertEquals("Fixture shop", activity.expenseViewModel.state.value.merchant)
                assertEquals("12.34", activity.expenseViewModel.state.value.amount)
                assertNotNull(activity.expenseViewModel.state.value.draft)
            }
        }
    }
}
