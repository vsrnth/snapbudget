package dev.snapbudget

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseEditDraft
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseRecord
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.domain.InMemoryMerchantCategoryRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.domain.MerchantCategoryRepository
import dev.snapbudget.domain.UpdateExpenseResult
import dev.snapbudget.ui.ExpensePage
import dev.snapbudget.ui.ExpenseScreen
import dev.snapbudget.ui.ExpenseViewModel
import dev.snapbudget.ui.SnapBudgetTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic local fixtures exercise confirmed editing without touching app storage. */
class ExpenseEditingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun editPrefillsExistingValuesAndCancelLeavesRecordAlone() {
        val repository = FixtureRepository().apply { seed(original) }
        val mappings = FixedMerchantMappings(mapOf("synthetic cafe" to "Travel"))
        val model = launch(repository, mappings)

        openEdit(1)
        compose.onNodeWithTag("edit-editor-title").assertIsDisplayed()
        compose.onNodeWithTag("choose-transaction-image").assertDoesNotExist()
        compose.onNodeWithTag("merchant-input").assertTextContains("Synthetic Cafe")
        compose.onNodeWithTag("amount-input").assertTextContains("10.00")
        compose.onNodeWithTag("datetime-input").assertTextContains("3 May 2025, 9:41 PM")
        compose.onNodeWithTag("category-input").assertTextContains("Food")
        assertEquals("Editing an existing expense keeps its stored category despite another merchant mapping", "Food", model.state.value.category)

        compose.onNodeWithTag("merchant-input").performScrollTo().performTextClearance()
        compose.onNodeWithTag("merchant-input").performTextInput("Unsaved Cafe")
        compose.onNodeWithTag("cancel-entry").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(original), repository.records.value)
        assertEquals(0, repository.updateCalls)
        assertEquals(0, repository.insertCalls)
        assertEquals(ExpensePage.LIST, model.state.value.page)
        compose.onNodeWithTag("expense-1").assertExists()
    }

    @Test fun explicitSaveUpdatesSameRecordAndChangesTotalDateAndCategory() {
        val repository = FixtureRepository().apply { seed(original) }
        val model = launch(repository)
        openEdit(1)
        replace("merchant-input", "Synthetic Bakery")
        replace("amount-input", "20.00")
        replace("datetime-input", "4 May 2025, 9:41 PM")
        selectCategory("Travel")
        assertEquals(0, repository.updateCalls)
        assertEquals(0, repository.insertCalls)

        compose.onNodeWithTag("save-edit").performScrollTo().assertTextContains("Save changes").performClick()
        compose.waitUntil(5_000) { model.state.value.page == ExpensePage.LIST && repository.updateCalls == 1 }
        compose.waitForIdle()

        assertEquals(1, repository.updateCalls)
        assertEquals(0, repository.insertCalls)
        assertEquals(0, repository.importCalls)
        assertEquals(1, repository.records.value.size)
        assertEquals(1L, repository.records.value.single().id)
        assertEquals(2_000L, repository.records.value.single().amountPaise)
        assertEquals("Synthetic Bakery", repository.records.value.single().merchant)
        assertEquals(LocalDateTime.of(2025, 5, 4, 21, 41), repository.records.value.single().dateTime)
        assertEquals("Travel", repository.records.value.single().category)
        compose.onNodeWithTag("expense-1").assertExists()
        compose.onNodeWithTag("expense-total").assertTextContains("₹20.00")
    }

    @Test fun invalidAmountAndDateDoNotCallUpdate() {
        val repository = FixtureRepository().apply { seed(original) }
        launch(repository)
        openEdit(1)
        replace("amount-input", "12.345")
        replace("datetime-input", "31 Feb 2025, 9:41 PM")
        compose.onNodeWithTag("save-edit").performScrollTo().performClick()
        compose.onNodeWithText("Enter an amount greater than ₹0, with up to two decimal places.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter a valid date and time, for example 3 May 2025, 9:41 PM.").performScrollTo().assertIsDisplayed()
        assertEquals(0, repository.updateCalls)
        assertEquals(0, repository.insertCalls)
        assertEquals(listOf(original), repository.records.value)
    }

    @Test fun failedUpdateKeepsEditedFieldsForRetry() {
        val repository = FixtureRepository().apply {
            seed(original)
            updateResult = UpdateExpenseResult.Failed
        }
        val model = launch(repository)
        openEdit(1)
        replace("merchant-input", "Retry Cafe")
        replace("amount-input", "22.50")
        compose.onNodeWithTag("save-edit").performScrollTo().performClick()
        compose.waitUntil(5_000) { !model.state.value.saving && model.state.value.errorMessage != null }
        compose.onNodeWithTag("recoverable-error").assertTextEquals("Expense could not be updated. Your edits are still here. Retry to try again.")
        compose.onNodeWithTag("merchant-input").assertTextContains("Retry Cafe")
        compose.onNodeWithTag("amount-input").assertTextContains("22.50")
        assertEquals(1, repository.updateCalls)
        assertEquals(listOf(original), repository.records.value)

        repository.updateResult = UpdateExpenseResult.Updated
        compose.onNodeWithTag("save-edit").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.page == ExpensePage.LIST }
        assertEquals(2, repository.updateCalls)
        assertEquals(0, repository.insertCalls)
        assertEquals(1L, repository.records.value.single().id)
        assertEquals("Retry Cafe", repository.records.value.single().merchant)
        assertEquals(2_250L, repository.records.value.single().amountPaise)
    }

    @Test fun pendingUpdateDisablesFieldsSaveAndCancelAndAcceptsOnlyOneTap() {
        val gate = CompletableDeferred<Unit>()
        val repository = FixtureRepository().apply { seed(original); updateGate = gate }
        val model = launch(repository)
        openEdit(1)
        replace("amount-input", "15.00")
        compose.onNodeWithTag("save-edit").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.saving }
        compose.onNodeWithTag("save-edit").assertIsNotEnabled().assertTextContains("Saving…")
        compose.onNodeWithTag("merchant-input").assertIsNotEnabled()
        compose.onNodeWithTag("amount-input").assertIsNotEnabled()
        compose.onNodeWithTag("datetime-input").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-entry").assertIsNotEnabled()
        compose.onNodeWithTag("save-edit").performClick()
        assertEquals(1, repository.updateCalls)
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val saveBounds = compose.onNodeWithTag("save-edit").fetchSemanticsNode().boundsInRoot
        assertTrue("Save changes is at least 48dp tall", saveBounds.height >= 48f * compose.density.density)
        assertTrue(saveBounds.left >= rootBounds.left && saveBounds.right <= rootBounds.right && saveBounds.top >= rootBounds.top && saveBounds.bottom <= rootBounds.bottom)
        gate.complete(Unit)
        compose.waitUntil(5_000) { model.state.value.page == ExpensePage.LIST }
        assertEquals(1, repository.updateCalls)
    }

    @Test fun missingEditedRecordShowsRecoverableErrorWithoutInsertingFallback() {
        val repository = FixtureRepository().apply { seed(original) }
        val model = launch(repository)
        openEdit(1)
        replace("amount-input", "33.00")
        repository.seed()
        compose.onNodeWithTag("save-edit").performScrollTo().performClick()
        compose.waitUntil(5_000) { !model.state.value.saving && model.state.value.errorMessage != null }
        compose.onNodeWithTag("recoverable-error").assertTextEquals("This expense was removed. Return to the list and edit an available expense.")
        assertEquals(1, repository.updateCalls)
        assertEquals(0, repository.insertCalls)
        assertTrue(repository.records.value.isEmpty())
        assertEquals(1L, model.state.value.editingId)
    }

    @Test fun restoredMonthEditKeepsDraftUntilCancelThenNewEntryHasNoEditingIdentity() {
        val repository = FixtureRepository().apply { seed(original) }
        val fixedClock = Clock.fixed(Instant.parse("2025-05-15T12:00:00Z"), ZoneId.of("UTC"))
        val saved = SavedStateHandle(mapOf(
            "page" to "EDIT", "editingId" to 1L, "returnPage" to "MONTH",
            "merchant" to "Unsaved Synthetic Cafe", "amount" to "77.00",
            "dateTime" to "2025-05-09T17:15", "category" to "Bills",
        ))
        val model = ExpenseViewModel(repository, saved, clock = fixedClock)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.waitForIdle()

        compose.onNodeWithTag("edit-editor-title").assertIsDisplayed()
        compose.onNodeWithTag("merchant-input").assertTextContains("Unsaved Synthetic Cafe")
        compose.onNodeWithTag("amount-input").assertTextContains("77.00")
        compose.onNodeWithTag("category-input").assertTextContains("Bills")
        compose.onNodeWithTag("save-edit").assertExists()
        assertEquals(0, repository.updateCalls)
        assertEquals(listOf(original), repository.records.value)
        compose.onNodeWithTag("cancel-entry").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.page == ExpensePage.MONTH }
        assertEquals(0, repository.updateCalls)

        compose.onNodeWithTag("add-expense").performScrollTo().performClick()
        assertEquals(ExpensePage.EDIT, model.state.value.page)
        assertEquals(null, model.state.value.editingId)
        compose.onNodeWithTag("editor-title").assertIsDisplayed()
        compose.onNodeWithTag("save-expense").assertExists()
        assertEquals(0, repository.updateCalls)
        assertEquals(0, repository.insertCalls)
    }

    @Test fun editorHasVisibleCategoryRenderingAndBoundedSaveGeometry() {
        val repository = FixtureRepository().apply { seed(original) }
        launch(repository)
        openEdit(1)
        val categoryField = compose.onNodeWithTag("category-input").performScrollTo()
        val image = categoryField.captureToImage()
        val pixels = image.toPixelMap()
        assertTrue(image.width > 2 && image.height > 2)
        val base = pixels[1, 1]
        assertTrue("Category editor field renders visible content", (0 until image.width).any { x -> (0 until image.height).any { y -> pixels[x, y] != base } })
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val saveBounds = compose.onNodeWithTag("save-edit").performScrollTo().fetchSemanticsNode().boundsInRoot
        assertTrue(saveBounds.width > 0f && saveBounds.height >= 48f * compose.density.density)
        assertTrue(saveBounds.left >= rootBounds.left && saveBounds.right <= rootBounds.right && saveBounds.top >= rootBounds.top && saveBounds.bottom <= rootBounds.bottom)
    }

    private fun launch(repository: FixtureRepository, mappings: MerchantCategoryRepository = InMemoryMerchantCategoryRepository()): ExpenseViewModel {
        val model = ExpenseViewModel(repository, merchantCategoryRepository = mappings, clock = fixtureClock)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.waitForIdle()
        return model
    }

    private fun openEdit(id: Long) {
        compose.onNodeWithTag("edit-$id").performScrollTo().performClick()
        compose.onNodeWithTag("edit-editor-title").assertIsDisplayed()
    }

    private fun replace(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
    }

    private fun selectCategory(category: String) {
        compose.onNodeWithTag("category-input").performScrollTo().performClick()
        compose.onNodeWithTag("category-option-$category").performScrollTo().performClick()
    }

    private class FixedMerchantMappings(private val mappings: Map<String, String>) : MerchantCategoryRepository {
        override suspend fun loadMappings(): Map<String, String> = mappings
        override suspend fun rememberCategory(merchant: String, category: String): Map<String, String> = mappings
    }

    private class FixtureRepository : ExpenseRepository, ImportedExpenseRepository {
        val records = MutableStateFlow<List<ExpenseRecord>>(emptyList())
        var insertCalls = 0
        var importCalls = 0
        var updateCalls = 0
        var updateResult: UpdateExpenseResult = UpdateExpenseResult.Updated
        var updateGate: CompletableDeferred<Unit>? = null

        fun seed(vararg expenses: ExpenseRecord) { records.value = expenses.toList() }

        override fun observeExpenses(): Flow<ExpenseObservation> = records.asStateFlow().map { ExpenseObservation.Records(it) }

        override suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult {
            insertCalls++
            return AddExpenseResult.Inserted(100)
        }

        override suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult {
            importCalls++
            return AddExpenseResult.Inserted(101)
        }

        override suspend fun updateExpense(draft: ExpenseEditDraft): UpdateExpenseResult {
            updateCalls++
            updateGate?.await()
            if (records.value.none { it.id == draft.id }) return UpdateExpenseResult.Missing
            if (updateResult != UpdateExpenseResult.Updated) return updateResult
            records.value = records.value.map { row ->
                if (row.id == draft.id) ExpenseRecord(draft.id, draft.amountPaise, draft.merchant, draft.dateTime, draft.category) else row
            }
            return UpdateExpenseResult.Updated
        }

        override suspend fun deleteExpense(id: Long): Boolean {
            val before = records.value.size
            records.value = records.value.filterNot { it.id == id }
            return records.value.size != before
        }
    }

    companion object {
        private val fixtureClock = Clock.fixed(Instant.parse("2025-05-15T12:00:00Z"), ZoneId.of("UTC"))
        private val original = ExpenseRecord(1, 1_000, "Synthetic Cafe", LocalDateTime.of(2025, 5, 3, 21, 41), "Food")
    }
}
