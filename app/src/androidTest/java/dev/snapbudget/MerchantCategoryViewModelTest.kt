package dev.snapbudget

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.snapbudget.application.ReceiptImagePreview
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageReader
import dev.snapbudget.application.ReceiptImageSelection
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseEditDraft
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseRecord
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.domain.MerchantCategoryCatalog
import dev.snapbudget.domain.MerchantCategoryRepository
import dev.snapbudget.domain.UpdateExpenseResult
import dev.snapbudget.ui.ExpensePage
import dev.snapbudget.ui.ExpenseViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic local integration checks for the confirmed-save merchant mapping behavior. */
class MerchantCategoryViewModelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmedManualSaveLearnsNormalizedMerchantForNextManualAndReceiptPrefill() {
        val repository = FixtureExpenseRepository()
        val mappings = FixtureMerchantCategoryRepository()
        val model = model(repository, mappings, reader = fixtureReader())
        setContent()
        await { model.state.value.loading.not() && mappings.loadCalls > 0 }

        compose.runOnIdle {
            model.openEditor()
            model.updateMerchant("  Synthetic   Cafe  ")
            model.updateAmount("12.34")
            model.updateDateTime("2025-05-03T21:41")
            model.updateCategory("Food")
            model.saveExpense()
        }
        await { repository.manualDrafts.size == 1 && !model.state.value.saving && mappings.writes.size == 1 }
        assertEquals("Synthetic   Cafe", repository.manualDrafts.single().merchant)
        assertEquals(listOf("synthetic cafe" to "Food"), mappings.writes)

        compose.runOnIdle {
            model.openEditor()
            model.updateMerchant("synthetic cafe")
        }
        assertEquals("Food", model.state.value.category)
        compose.runOnIdle { model.cancelEditor() }

        compose.runOnIdle { model.onTransactionImageSelected(ReceiptImageSelection("synthetic-image-selection")) }
        await { !model.state.value.readingImage && model.state.value.sourceImageHash != null && model.state.value.category == "Food" }
        assertEquals("Synthetic   Cafe", model.state.value.merchant)
        assertEquals("Food", model.state.value.category)
        assertTrue(mappings.writes.size == 1)
    }

    @Test fun explicitOverrideWinsDelayedMappingAndBecomesTheNextSuggestion() {
        val repository = FixtureExpenseRepository()
        val mappings = FixtureMerchantCategoryRepository(
            initial = mapOf("synthetic cafe" to "Food"),
            holdLoad = true,
        )
        val model = model(repository, mappings)
        setContent()
        await { mappings.loadStarted.isCompleted }
        compose.runOnIdle {
            model.openEditor()
            model.updateMerchant("Synthetic Cafe")
            model.updateCategory("Shopping")
        }
        assertTrue(model.state.value.explicitCategorySelection)
        mappings.releaseLoad()
        await { mappings.loadFinished.isCompleted }
        assertEquals("Shopping", model.state.value.category)

        compose.runOnIdle {
            model.updateAmount("22.00")
            model.updateDateTime("2025-05-03T21:41")
            model.saveExpense()
        }
        await { repository.manualDrafts.size == 1 && !model.state.value.saving && mappings.writes.size == 1 }
        assertEquals(listOf("synthetic cafe" to "Shopping"), mappings.writes)

        compose.runOnIdle {
            model.openEditor()
            model.updateMerchant("  synthetic    cafe ")
        }
        assertEquals("Shopping", model.state.value.category)
    }

    @Test fun customMappedCategoryAppearsInAvailableCategoryOptions() {
        val repository = FixtureExpenseRepository()
        val mappings = FixtureMerchantCategoryRepository(initial = mapOf("synthetic cafe" to "Coffee"))
        val model = model(repository, mappings)
        setContent()
        await { mappings.loadFinished.isCompleted }
        compose.runOnIdle {
            model.openEditor()
            model.updateMerchant("Synthetic Cafe")
        }
        assertEquals("Coffee", model.state.value.category)
        assertTrue(model.state.value.categoryOptions.contains("Coffee"))
    }

    @Test fun restoredExplicitChoiceAndEditedHistoricalCategorySurviveAsyncMappingAndHistoryFallbackWorks() {
        val record = ExpenseRecord(41, 700L, "Old Synthetic Shop", LocalDateTime.of(2025, 5, 1, 10, 0), "Groceries")
        val repository = FixtureExpenseRepository(initial = listOf(record))
        val mappings = FixtureMerchantCategoryRepository(initial = mapOf("old synthetic shop" to "Food"), holdLoad = true)
        val saved = SavedStateHandle(
            mapOf(
                "page" to ExpensePage.EDIT.name,
                "merchant" to "Saved Synthetic Shop",
                "amount" to "1.00",
                "dateTime" to "2025-05-03T21:41",
                "category" to "Bills",
                "explicitCategorySelection" to true,
            ),
        )
        val restored = ExpenseViewModel(repository, saved, merchantCategoryRepository = mappings, clock = FIXED_CLOCK)
        setContent()
        await { restored.state.value.expenses.size == 1 && mappings.loadStarted.isCompleted }
        mappings.releaseLoad()
        await { mappings.loadFinished.isCompleted }
        assertEquals("Bills", restored.state.value.category)
        assertTrue(restored.state.value.explicitCategorySelection)

        val delayedEditMappings = FixtureMerchantCategoryRepository(initial = mapOf("old synthetic shop" to "Food"), holdLoad = true)
        val edited = model(repository, delayedEditMappings)
        await { edited.state.value.expenses.size == 1 && delayedEditMappings.loadStarted.isCompleted }
        compose.runOnIdle { edited.editExpense(record.id) }
        assertEquals("Groceries", edited.state.value.category)
        delayedEditMappings.releaseLoad()
        await { delayedEditMappings.loadFinished.isCompleted }
        assertEquals("Groceries", edited.state.value.category)

        val historicalMappings = FixtureMerchantCategoryRepository()
        val historicalModel = model(repository, historicalMappings)
        await { historicalModel.state.value.expenses.size == 1 && historicalMappings.loadFinished.isCompleted }
        compose.runOnIdle {
            historicalModel.openEditor()
            historicalModel.updateMerchant(" old   synthetic  shop ")
        }
        assertEquals("Groceries", historicalModel.state.value.category)
    }

    @Test fun cancelledInvalidFailedAndDuplicateDraftsNeverLearnAndMappingFailureDoesNotUndoSave() {
        val repository = FixtureExpenseRepository().apply { manualResults.add(AddExpenseResult.Failed) }
        val mappings = FixtureMerchantCategoryRepository()
        val model = model(repository, mappings, reader = fixtureReader())
        setContent()
        await { mappings.loadFinished.isCompleted }

        compose.runOnIdle {
            model.openEditor()
            model.updateMerchant("Synthetic Cafe")
            model.updateAmount("oops")
            model.saveExpense()
        }
        await { model.state.value.errors.isNotEmpty() }
        assertEquals(0, repository.manualCalls)
        assertTrue(mappings.writes.isEmpty())

        compose.runOnIdle {
            model.cancelEditor()
            model.openEditor()
            model.updateMerchant("Synthetic Cafe")
            model.updateAmount("12.00")
            model.updateDateTime("2025-05-03T21:41")
            model.saveExpense()
        }
        await { !model.state.value.saving && model.state.value.errorMessage != null }
        assertEquals(1, repository.manualCalls)
        assertTrue(mappings.writes.isEmpty())

        compose.runOnIdle { model.cancelEditor(); model.onTransactionImageSelected(ReceiptImageSelection("synthetic-duplicate")) }
        await { !model.state.value.readingImage && model.state.value.sourceImageHash != null }
        repository.importResult = AddExpenseResult.Duplicate
        compose.runOnIdle { model.saveExpense() }
        await { model.state.value.duplicateImport }
        assertEquals(1, repository.importCalls)
        assertTrue(mappings.writes.isEmpty())

        val successRepo = FixtureExpenseRepository()
        val failingMappings = FixtureMerchantCategoryRepository(failRemember = true)
        val successModel = model(successRepo, failingMappings)
        await { failingMappings.loadFinished.isCompleted }
        compose.runOnIdle {
            successModel.openEditor()
            successModel.updateMerchant("Synthetic Cafe")
            successModel.updateAmount("3.25")
            successModel.updateDateTime("2025-05-03T21:41")
            successModel.updateCategory("Food")
            successModel.saveExpense()
        }
        await { successRepo.records.value.size == 1 && successModel.state.value.errorMessage?.contains("could not be remembered") == true }
        assertEquals(1, successRepo.manualCalls)
        compose.runOnIdle { successModel.saveExpense() }
        assertEquals(1, successRepo.manualCalls)
        assertEquals("Expenses remain saved when optional merchant mapping storage fails", 1, successModel.state.value.expenses.size)
    }

    @Test fun failedRetryReusesOperationTokenAndRapidRepeatedSaveLearnsOnlyOnce() {
        val repository = FixtureExpenseRepository().apply {
            manualResults.add(AddExpenseResult.Failed)
            manualResults.add(AddExpenseResult.Inserted(100))
            manualGate = CompletableDeferred()
            manualGateCall = 2
        }
        val mappings = FixtureMerchantCategoryRepository()
        val model = model(repository, mappings)
        setContent()
        await { mappings.loadFinished.isCompleted }
        compose.runOnIdle {
            model.openEditor()
            model.updateMerchant("Retry Cafe")
            model.updateAmount("8.00")
            model.updateDateTime("2025-05-03T21:41")
            model.updateCategory("Travel")
            model.saveExpense()
        }
        await { repository.manualCalls == 1 && !model.state.value.saving }
        val firstToken = repository.manualDrafts.first().operationToken

        compose.runOnIdle { model.saveExpense() }
        await { repository.manualCalls == 2 && model.state.value.saving }
        compose.runOnIdle { model.saveExpense() }
        assertEquals(2, repository.manualCalls)
        repository.manualGate!!.complete(Unit)
        await { !model.state.value.saving && mappings.writes.size == 1 }
        assertEquals(listOf(firstToken, firstToken), repository.manualDrafts.map { it.operationToken })
        assertEquals(listOf("retry cafe" to "Travel"), mappings.writes)
    }

    private fun model(
        repository: FixtureExpenseRepository,
        mappings: FixtureMerchantCategoryRepository,
        reader: ReceiptImageReader? = null,
    ) = ExpenseViewModel(
        repository = repository,
        receiptImageReader = reader,
        importedExpenseRepository = repository,
        merchantCategoryRepository = mappings,
        clock = FIXED_CLOCK,
    )

    private fun setContent() {
        compose.setContent { }
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 3_000L, condition = condition)
    }

    private fun fixtureReader() = object : ReceiptImageReader {
        override suspend fun read(selection: ReceiptImageSelection) = ReceiptImageReadResult.Ready(
            ReceiptImagePreview(
                amountPaise = 1_234L,
                merchant = "Synthetic   Cafe",
                dateTime = LocalDateTime.of(2025, 5, 3, 21, 41),
                imageHash = "a".repeat(64),
                transactionId = "T123456789012345",
            ),
        )
    }

    private class FixtureExpenseRepository(initial: List<ExpenseRecord> = emptyList()) : ExpenseRepository, ImportedExpenseRepository {
        val records = MutableStateFlow(initial)
        val manualDrafts = mutableListOf<ManualExpenseDraft>()
        val importedDrafts = mutableListOf<ImportedExpenseDraft>()
        val manualResults = ArrayDeque<AddExpenseResult>()
        var manualCalls = 0
        var importCalls = 0
        var importResult: AddExpenseResult = AddExpenseResult.Inserted(200)
        var manualGate: CompletableDeferred<Unit>? = null
        var manualGateCall: Int = Int.MAX_VALUE

        override fun observeExpenses(): Flow<ExpenseObservation> = records.asStateFlow().map { ExpenseObservation.Records(it) }

        override suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult {
            manualCalls++
            manualDrafts += draft
            if (manualCalls == manualGateCall) manualGate?.await()
            val result = if (manualResults.isEmpty()) AddExpenseResult.Inserted((records.value.size + 1).toLong()) else manualResults.removeFirst()
            if (result is AddExpenseResult.Inserted) {
                records.value = records.value + ExpenseRecord(result.id, draft.amountPaise, draft.merchant, draft.dateTime, draft.category)
            }
            return result
        }

        override suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult {
            importCalls++
            importedDrafts += draft
            if (importResult is AddExpenseResult.Inserted) {
                val id = (importResult as AddExpenseResult.Inserted).id
                records.value = records.value + ExpenseRecord(id, draft.amountPaise, draft.merchant, draft.dateTime, draft.category)
            }
            return importResult
        }

        override suspend fun updateExpense(draft: ExpenseEditDraft): UpdateExpenseResult = UpdateExpenseResult.Missing
        override suspend fun deleteExpense(id: Long): Boolean = false
    }

    private class FixtureMerchantCategoryRepository(
        initial: Map<String, String> = emptyMap(),
        private val holdLoad: Boolean = false,
        private val failRemember: Boolean = false,
    ) : MerchantCategoryRepository {
        private val values = initial.toMutableMap()
        val writes = mutableListOf<Pair<String, String>>()
        val loadStarted = CompletableDeferred<Unit>()
        val loadFinished = CompletableDeferred<Unit>()
        var loadCalls = 0
        private val loadGate = if (holdLoad) CompletableDeferred<Unit>() else null

        override suspend fun loadMappings(): Map<String, String> {
            loadCalls++
            loadStarted.complete(Unit)
            loadGate?.await()
            loadFinished.complete(Unit)
            return values.toMap()
        }

        override suspend fun rememberCategory(merchant: String, category: String): Map<String, String> {
            writes += MerchantCategoryCatalog.key(merchant) to category.trim()
            if (failRemember) error("synthetic mapping failure")
            values[MerchantCategoryCatalog.key(merchant)] = category.trim()
            return values.toMap()
        }

        fun releaseLoad() { loadGate?.complete(Unit) }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2025-05-03T21:41:00Z"), ZoneId.of("UTC"))
    }
}
