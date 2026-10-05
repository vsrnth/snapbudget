package dev.snapbudget

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseRecord
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.domain.CategoryRepository
import dev.snapbudget.application.ReceiptImageReader
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImagePreview
import dev.snapbudget.application.ReceiptImageSelection
import dev.snapbudget.ui.ExpensePage
import dev.snapbudget.ui.ExpenseScreen
import dev.snapbudget.ui.ExpenseViewModel
import dev.snapbudget.ui.SnapBudgetTheme
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic UI coverage uses only an in-memory fixture repository, never the app database. */
class ExpenseScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyStateAndInvalidAmountAreAccessibleAndDoNotWrite() {
        val repository = FixtureRepository()
        launch(repository)
        compose.onNodeWithTag("app-title").assertExists()
        compose.onNodeWithTag("empty-state").assertExists()
        compose.onNodeWithTag("privacy-note").assertExists()
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val totalBounds = compose.onNodeWithTag("total-card").fetchSemanticsNode().boundsInRoot
        assertTrue("Total card must remain inside the screen viewport", totalBounds.left >= rootBounds.left && totalBounds.right <= rootBounds.right && totalBounds.top >= rootBounds.top)
        assertTrue("Total card should have a non-zero visual surface", totalBounds.width > 0f && totalBounds.height > 0f)
        compose.onNodeWithTag("add-expense").performClick()
        compose.onNodeWithTag("amount-input").performTextInput("12.345")
        compose.onNodeWithTag("save-expense").performClick()
        compose.onNodeWithText("Enter an amount greater than ₹0, with up to two decimal places.").assertExists()
        assertEquals(0, repository.insertCalls)
    }

    @Test fun categoryDropdownIsReadOnlyAndCustomCategoriesPersistAcrossEditorCancellation() {
        val repository = FixtureRepository()
        val categories = FixtureCategoryRepository()
        var model by mutableStateOf(ExpenseViewModel(repository, categoryRepository = categories))
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.waitForIdle()
        compose.onNodeWithTag("add-expense").performClick()
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInWindow
        val field = compose.onNodeWithTag("category-input").performScrollTo()
        val config = field.fetchSemanticsNode().config
        assertTrue("Category is selected from a menu rather than typed", !config.contains(SemanticsActions.SetText))
        val fieldImage = field.captureToImage()
        val fieldPixels = fieldImage.toPixelMap()
        assertTrue(fieldImage.width > 2 && fieldImage.height > 2)
        val surfacePixel = fieldPixels[2, 2]
        assertTrue(fieldImage.width > 0 && fieldImage.height > 0)
        assertTrue("Category field renders a visible outline or text", (0 until fieldImage.width).any { x -> (0 until fieldImage.height).any { y -> fieldPixels[x, y] != surfacePixel } })
        field.performClick()
        val addChoice = compose.onNodeWithTag("add-category-option").performScrollTo().assertIsDisplayed()
        val bounds = addChoice.fetchSemanticsNode().boundsInWindow
        assertTrue(bounds.width > 0f && bounds.height >= 48f * compose.density.density)
        assertTrue(bounds.left >= rootBounds.left && bounds.right <= rootBounds.right && bounds.top >= rootBounds.top && bounds.bottom <= rootBounds.bottom)
        compose.onNodeWithTag("category-option-Food").performClick()
        assertEquals("Food", model.state.value.category)

        compose.onNodeWithTag("category-input").performClick()
        compose.onNodeWithTag("add-category-option").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("new-category-input").performTextInput("  Coffee  ")
        compose.onNodeWithTag("add-category-confirm").performClick()
        compose.waitForIdle()
        assertEquals("Coffee", model.state.value.category)
        assertEquals(listOf("Coffee"), categories.values)
        compose.onNodeWithTag("cancel-entry").performScrollTo().performClick()

        compose.onNodeWithTag("add-expense").performClick()
        compose.onNodeWithTag("category-input").performScrollTo().performClick()
        compose.onNodeWithTag("category-option-Coffee").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("Coffee", model.state.value.category)
        compose.onNodeWithTag("category-input").performClick()
        compose.onNodeWithTag("add-category-option").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("new-category-input").performTextInput("cOffEe")
        compose.onNodeWithTag("add-category-confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Coffee"), categories.values)
        assertEquals(1, model.state.value.categoryOptions.count { it.equals("coffee", ignoreCase = true) })
        assertEquals("Coffee", model.state.value.category)
        compose.onNodeWithTag("cancel-entry").performScrollTo().performClick()
        val restarted = ExpenseViewModel(repository, categoryRepository = categories)
        compose.runOnIdle { model = restarted }
        compose.waitForIdle()
        assertTrue(restarted.state.value.categoryOptions.contains("Coffee"))
    }

    @Test fun categoryAddValidationAndCancelLeaveCurrentSelectionAlone() {
        val model = ExpenseViewModel(FixtureRepository(), categoryRepository = FixtureCategoryRepository())
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.waitForIdle()
        compose.onNodeWithTag("add-expense").performClick()
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInWindow
        compose.onNodeWithTag("category-input").performScrollTo().performClick()
        compose.onNodeWithTag("add-category-option").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("new-category-input").performTextInput("\n")
        compose.onNodeWithTag("add-category-confirm").performClick()
        compose.onNodeWithTag("category-error", useUnmergedTree = true).assertTextEquals("Category names cannot contain control characters.")
        val dialogBounds = compose.onNodeWithTag("new-category-input").fetchSemanticsNode().boundsInWindow
        assertTrue(dialogBounds.width > 0f && dialogBounds.height > 0f)
        assertTrue(dialogBounds.left >= rootBounds.left && dialogBounds.right <= rootBounds.right && dialogBounds.top >= rootBounds.top && dialogBounds.bottom <= rootBounds.bottom)
        compose.onNodeWithTag("add-category-cancel").performClick()
        assertEquals("Other", model.state.value.category)
        assertTrue(model.state.value.categoryOptions.none { it == "" })
    }

    @Test fun directSaveWritesOnlyOnTapAndSupportsExplicitDelete() {
        val repository = FixtureRepository()
        launch(repository)
        compose.onNodeWithTag("add-expense").performClick()
        fillValidForm()
        compose.onNodeWithTag("cancel-entry").performScrollTo().performClick()
        assertEquals("Cancel leaves a draft unsaved", 0, repository.insertCalls)
        compose.onNodeWithTag("add-expense").performClick()
        fillValidForm()
        assertEquals(0, repository.insertCalls)
        compose.onNodeWithTag("save-expense").performScrollTo().assertTextContains("Save expense")
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("expense-1").assertExists()
        compose.onNodeWithTag("expense-total").assertTextContains("₹125.50")
        compose.onNodeWithTag("expense-count").assertTextContains("1 expense")
        assertEquals(1, repository.insertCalls)
        compose.onNodeWithTag("delete-1").performClick()
        compose.onNodeWithTag("cancel-delete").performClick()
        compose.onNodeWithTag("expense-1").assertExists()
        compose.onNodeWithTag("delete-1").performClick()
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("empty-state").assertExists()
        assertEquals(1, repository.deleteCalls)
    }
    @Test fun readableDateInputValidatesAndSavesCanonicalTimeOnExplicitTap() {
        val repository = FixtureRepository()
        val saved = SavedStateHandle(mapOf("page" to "EDIT", "merchant" to "", "amount" to "", "category" to "Other", "dateTime" to "2025-05-03T21:41"))
        val model = ExpenseViewModel(repository, saved)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.onNodeWithTag("merchant-input").performTextInput("Synthetic Cafe")
        compose.onNodeWithTag("amount-input").performTextInput("12.34")
        val dateField = compose.onNodeWithTag("datetime-input").performScrollTo()
        dateField.assertTextContains("Example: 3 May 2025, 9:41 PM")
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val dateBounds = dateField.fetchSemanticsNode().boundsInRoot
        assertTrue(dateBounds.width > 0f && dateBounds.height > 0f)
        assertTrue(dateBounds.left >= rootBounds.left && dateBounds.right <= rootBounds.right)
        assertTrue(dateBounds.top >= rootBounds.top && dateBounds.bottom <= rootBounds.bottom)
        dateField.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString("3 May 2025, 9:41 PM"),
        ))
        dateField.performTextClearance()
        dateField.performTextInput("31 Feb 2025, 9:41 PM")
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.onNodeWithText("Enter a valid date and time, for example 3 May 2025, 9:41 PM.").performScrollTo().assertExists()
        assertEquals(0, repository.insertCalls)
        dateField.performTextClearance()
        dateField.performTextInput("29 Feb 2025, 9:41 PM")
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.onNodeWithText("Enter a valid date and time, for example 3 May 2025, 9:41 PM.").performScrollTo().assertExists()
        assertEquals(0, repository.insertCalls)
        dateField.performTextClearance()
        dateField.performTextInput("3 May 2025, 9:41 PM")
        assertEquals("2025-05-03T21:41", saved.get<String>("dateTime"))
        assertEquals(0, repository.insertCalls)
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, repository.insertCalls)
        assertEquals(LocalDateTime.of(2025, 5, 3, 21, 41), repository.savedDateTimes.single())
        compose.onNodeWithTag("expense-1").performScrollTo().assertExists()
        compose.onNodeWithText("Other · 3 May 2025, 9:41 PM").performScrollTo().assertExists()
    }
    @Test fun importedPrefillIsEditableAndSaveKeepsIdentityWithoutDisplayingHash() {
        val repository = FixtureRepository()
        val hash = "a".repeat(64)
        val reader = object : ReceiptImageReader {
            override suspend fun read(selection: ReceiptImageSelection) = ReceiptImageReadResult.Ready(ReceiptImagePreview(null, "Synthetic Cafe", null, hash, "T12345678901234567"))
        }
        val model = ExpenseViewModel(repository, receiptImageReader = reader, importedExpenseRepository = repository)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model, onChooseTransactionImage = { model.openImageImport() }) } }
        compose.onNodeWithTag("add-expense").performClick()
        compose.onNodeWithTag("choose-transaction-image").performScrollTo().performClick()
        model.onTransactionImageSelected(ReceiptImageSelection("fixture"))
        compose.waitForIdle()
        compose.onNodeWithTag("transaction-identity").performScrollTo().assertTextEquals("Transaction ID: T12345678901234567")
        compose.onNodeWithText(hash).assertDoesNotExist()
        assertEquals(0, repository.importCalls)
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.onNodeWithText("Enter an amount greater than ₹0, with up to two decimal places.").performScrollTo().assertExists()
        compose.onNodeWithTag("amount-input").performScrollTo().performTextInput("13.37")
        compose.onNodeWithTag("datetime-input").performScrollTo().performTextInput("3 May 2025, 9:41 PM")
        compose.onNodeWithTag("merchant-input").performScrollTo().performTextClearance()
        compose.onNodeWithTag("merchant-input").performTextInput("Corrected Cafe")
        compose.onNodeWithTag("category-input").performScrollTo().performClick()
        compose.onNodeWithTag("category-option-Food").performClick()
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, repository.importCalls)
        assertEquals(1337L, repository.importedDrafts.single().amountPaise)
        assertEquals("Corrected Cafe", repository.importedDrafts.single().merchant)
        assertEquals(hash, repository.importedDrafts.single().imageHash)
        assertEquals("T12345678901234567", repository.importedDrafts.single().transactionId)
        assertEquals(LocalDateTime.of(2025, 5, 3, 21, 41), repository.importedDrafts.single().dateTime)
        assertEquals("Food", repository.importedDrafts.single().category)
    }
    @Test fun categoryLoadAndWriteFailuresCanBeRetriedWithoutSelectingUnsavedCategory() {
        val categories = FixtureCategoryRepository(failFirstLoad = true, failFirstAdd = true)
        val model = ExpenseViewModel(FixtureRepository(), categoryRepository = categories)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.waitForIdle()
        compose.onNodeWithTag("add-expense").performClick()
        compose.onNodeWithTag("category-input").performScrollTo().performClick()
        compose.onNodeWithTag("add-category-option").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("new-category-input").performTextInput("Coffee")
        compose.onNodeWithTag("add-category-confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("category-error", useUnmergedTree = true).assertTextEquals("Category could not be saved. Please try again.")
        assertEquals("Other", model.state.value.category)
        assertTrue(model.state.value.categoryCatalogLoaded)
        compose.onNodeWithTag("add-category-confirm").performClick()
        compose.waitForIdle()
        assertEquals("Coffee", model.state.value.category)
        assertEquals(listOf("Coffee"), categories.values)
    }

    @Test fun listImageActionUsesActivityCallbackAndPickerCancellationKeepsList() {
        val repository = FixtureRepository()
        val model = ExpenseViewModel(repository)
        var pickerLaunches = 0
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model, onChooseTransactionImage = {
            pickerLaunches++
            model.openImageImport()
        }) } }
        compose.onNodeWithTag("choose-image-list").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, pickerLaunches)
        compose.onNodeWithTag("empty-state").assertExists()
        model.onImageSelectionCancelled()
        compose.waitForIdle()
        compose.onNodeWithTag("empty-state").assertExists()
        assertEquals(0, repository.insertCalls)
        assertEquals(0, repository.importCalls)
    }

    @Test fun syntheticRecognizedAmountPrefillsEditorAndWaitsForSaveTap() {
        val repository = FixtureRepository()
        val hash = "f".repeat(64)
        val reader = object : ReceiptImageReader {
            override suspend fun read(selection: ReceiptImageSelection) = ReceiptImageReadResult.Ready(
                ReceiptImagePreview(
                    24_568, "Synthetic Bakery", LocalDateTime.of(2025, 5, 3, 21, 41),
                    hash, "T987654321098765432",
                ),
            )
        }
        val model = ExpenseViewModel(repository, receiptImageReader = reader, importedExpenseRepository = repository)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model, onChooseTransactionImage = { model.openImageImport() }) } }
        compose.onNodeWithTag("add-expense").performClick()
        compose.onNodeWithTag("choose-transaction-image").performScrollTo().performClick()
        model.onTransactionImageSelected(ReceiptImageSelection("synthetic-layout-fixture"))
        compose.waitForIdle()
        compose.onNodeWithTag("amount-input").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("245.68")))
        compose.onNodeWithText(hash).assertDoesNotExist()
        assertEquals(0, repository.importCalls)
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, repository.importCalls)
        assertEquals(24_568L, repository.importedDrafts.single().amountPaise)
        assertEquals(hash, repository.importedDrafts.single().imageHash)
    }
    @Test fun failedImportCanRetryWithoutChangingOriginalIdentity() {
        val repository = FixtureRepository().apply { importResult = AddExpenseResult.Failed }
        val hash = "b".repeat(64)
        val reader = object : ReceiptImageReader {
            override suspend fun read(selection: ReceiptImageSelection) = ReceiptImageReadResult.Ready(
                ReceiptImagePreview(2500, "Synthetic Shop", LocalDateTime.of(2025, 4, 1, 12, 0), hash, null),
            )
        }
        val model = ExpenseViewModel(repository, receiptImageReader = reader, importedExpenseRepository = repository)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model, onChooseTransactionImage = { model.openImageImport() }) } }
        compose.onNodeWithTag("add-expense").performClick()
        compose.onNodeWithTag("choose-transaction-image").performScrollTo().performClick()
        model.onTransactionImageSelected(ReceiptImageSelection("fixture"))
        compose.waitForIdle()
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recoverable-error").assertTextEquals("Expense could not be saved. Retry to try again.")
        assertEquals("Synthetic Shop", model.state.value.merchant)
        assertEquals(hash, model.state.value.sourceImageHash)
        assertEquals(null, model.state.value.sourceTransactionId)
        repository.importResult = AddExpenseResult.Inserted(9)
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, repository.importCalls)
        assertEquals(listOf(hash, hash), repository.importedDrafts.map { it.imageHash })
        compose.onNodeWithTag("expense-2").assertExists()
    }
    @Test fun duplicateImportOffersReturnAndBlocksSecondSave() {
        val repository = FixtureRepository().apply { importResult = AddExpenseResult.Duplicate }
        val hash = "c".repeat(64)
        val reader = object : ReceiptImageReader {
            override suspend fun read(selection: ReceiptImageSelection) = ReceiptImageReadResult.Ready(
                ReceiptImagePreview(2500, "Synthetic Shop", LocalDateTime.of(2025, 4, 1, 12, 0), hash, null),
            )
        }
        val model = ExpenseViewModel(repository, receiptImageReader = reader, importedExpenseRepository = repository)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model, onChooseTransactionImage = { model.openImageImport() }) } }
        compose.onNodeWithTag("choose-image-list").performScrollTo().performClick()
        model.onTransactionImageSelected(ReceiptImageSelection("fixture"))
        compose.waitForIdle()
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recoverable-error").assertTextEquals("This receipt was already imported. No second expense was created.")
        compose.onNodeWithTag("save-expense").assertIsNotEnabled()
        compose.onNodeWithTag("return-after-duplicate").performScrollTo().performClick()
        compose.onNodeWithTag("empty-state").assertExists()
        assertEquals(1, repository.importCalls)
    }
    @Test fun rapidSaveTapIsBlockedWhileWriteIsInProgress() {
        val repository = FixtureRepository()
        launch(repository)
        compose.onNodeWithTag("add-expense").performClick()
        fillValidForm()
        repository.insertGate = CompletableDeferred()
        compose.onNodeWithTag("save-expense").performClick()
        compose.onNodeWithTag("save-expense").assertIsNotEnabled().assertTextEquals("Saving…")
        compose.onNodeWithTag("merchant-input").assertIsNotEnabled()
        compose.onNodeWithTag("amount-input").assertIsNotEnabled()
        compose.onNodeWithTag("datetime-input").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-entry").assertIsNotEnabled()
        compose.onNodeWithTag("save-expense").performClick()
        repository.insertGate!!.complete(Unit)
        compose.waitForIdle()
        assertEquals(1, repository.insertCalls)
    }
    @Test fun readyImportFromListRestoresEditorAndSourceIdentity() {
        val repository = FixtureRepository()
        val hash = "d".repeat(64)
        val reader = object : ReceiptImageReader {
            override suspend fun read(selection: ReceiptImageSelection) = ReceiptImageReadResult.Ready(
                ReceiptImagePreview(4500, "Synthetic Market", LocalDateTime.of(2025, 6, 1, 9, 30), hash, "T12345678901234567"),
            )
        }
        val saved = SavedStateHandle()
        val model = ExpenseViewModel(repository, saved, reader, repository)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        model.onTransactionImageSelected(ReceiptImageSelection("synthetic"))
        compose.waitForIdle()
        assertEquals(ExpensePage.EDIT, model.state.value.page)
        assertEquals(ExpensePage.EDIT.name, saved.get<String>("page"))

        val restored = ExpenseViewModel(repository, savedStateCopy(saved), reader, repository)
        assertEquals(ExpensePage.EDIT, restored.state.value.page)
        assertEquals(hash, restored.state.value.sourceImageHash)
        assertEquals("T12345678901234567", restored.state.value.sourceTransactionId)
        assertEquals(0, repository.importCalls)
    }

    @Test fun openingManualEditorRemovesRestoredImportIdentity() {
        val repository = FixtureRepository()
        val saved = SavedStateHandle(mapOf(
            "page" to "LIST", "importImageHash" to "e".repeat(64),
            "importTransactionId" to "T12345678901234567",
        ))
        val model = ExpenseViewModel(repository, saved)
        model.openEditor()
        assertEquals(null, saved.get<String>("importImageHash"))
        assertEquals(null, saved.get<String>("importTransactionId"))

        val restored = ExpenseViewModel(repository, savedStateCopy(saved))
        assertEquals(ExpensePage.EDIT, restored.state.value.page)
        assertEquals(null, restored.state.value.sourceImageHash)
        assertEquals(null, restored.state.value.sourceTransactionId)
        assertEquals(0, repository.importCalls)
    }

    @Test fun legacyReviewStateMigratesToEditorWithStableTokenAndNoAutomaticWrite() {
        val repository = FixtureRepository()
        val saved = SavedStateHandle(mapOf("page" to "REVIEW", "merchant" to "Synthetic Cafe", "amount" to "125.50", "dateTime" to "2025-05-03T21:41", "category" to "Food", "operationToken" to "manual:stable-token"))
        val model = ExpenseViewModel(repository, saved)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.onNodeWithTag("merchant-input").assertExists()
        compose.onNodeWithTag("save-expense").assertExists()
        compose.onNodeWithTag("review-title").assertDoesNotExist()
        assertEquals(0, repository.insertCalls)
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("manual:stable-token"), repository.tokens)
    }

    @Test fun legacyReviewImportMigratesWithHashAndTransactionIdentityWithoutWriting() {
        val repository = FixtureRepository()
        val hash = "9".repeat(64)
        val saved = SavedStateHandle(mapOf(
            "page" to "REVIEW", "merchant" to "Synthetic Shop", "amount" to "45.67",
            "dateTime" to "2025-06-01T09:30", "category" to "Food",
            "importImageHash" to hash, "importTransactionId" to "T12345678901234567",
        ))
        val model = ExpenseViewModel(repository, saved, importedExpenseRepository = repository)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.onNodeWithTag("merchant-input").assertExists()
        compose.onNodeWithTag("transaction-identity").assertTextEquals("Transaction ID: T12345678901234567")
        compose.onNodeWithText(hash).assertDoesNotExist()
        assertEquals(ExpensePage.EDIT, model.state.value.page)
        assertEquals(0, repository.importCalls)
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, repository.importCalls)
        assertEquals(hash, repository.importedDrafts.single().imageHash)
        assertEquals("T12345678901234567", repository.importedDrafts.single().transactionId)
    }
    @Test fun legacyCanonicalSavedStateRestoresAsReadableEditableDateAndKeepsImportIdentity() {
        val repository = FixtureRepository()
        val saved = SavedStateHandle(mapOf(
            "page" to "EDIT", "merchant" to "Synthetic Cafe", "amount" to "125.50",
            "dateTime" to "2025-05-03T21:41", "category" to "Food",
            "importImageHash" to "f".repeat(64), "importTransactionId" to "TX-SYNTHETIC-1",
        ))
        val model = ExpenseViewModel(repository, saved)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        val dateField = compose.onNodeWithTag("datetime-input").performScrollTo()
        dateField.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString("3 May 2025, 9:41 PM"),
        ))
        compose.onNodeWithTag("transaction-identity").performScrollTo()
            .assertTextEquals("Transaction ID: TX-SYNTHETIC-1")
        assertEquals("2025-05-03T21:41", saved.get<String>("dateTime"))
        assertEquals("f".repeat(64), saved.get<String>("importImageHash"))
        assertEquals("TX-SYNTHETIC-1", saved.get<String>("importTransactionId"))
    }

    private fun savedStateCopy(source: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(source.keys().associateWith { source.get<Any>(it) })

    private fun launch(repository: FixtureRepository) {
        val model = ExpenseViewModel(repository)
        compose.setContent { SnapBudgetTheme { ExpenseScreen(model) } }
        compose.waitForIdle()
    }

    private fun fillValidForm() {
        compose.onNodeWithTag("merchant-input").performScrollTo().performTextInput("Synthetic Cafe")
        compose.onNodeWithTag("amount-input").performScrollTo().performTextInput("125.50")
        compose.onNodeWithTag("category-input").performScrollTo().performClick()
        compose.onNodeWithTag("category-option-Food").performClick()
    }

    private class FixtureRepository : ExpenseRepository, ImportedExpenseRepository {
        private val records = MutableStateFlow<List<ExpenseRecord>>(emptyList())
        val savedDateTimes = mutableListOf<LocalDateTime>()
        var insertCalls = 0
        var deleteCalls = 0
        var importCalls = 0
        val importedDrafts = mutableListOf<ImportedExpenseDraft>()
        var importResult: AddExpenseResult = AddExpenseResult.Inserted(2)
        val tokens = mutableListOf<String>()
        var insertGate: CompletableDeferred<Unit>? = null
        override fun observeExpenses(): Flow<ExpenseObservation> = records.asStateFlow().map { ExpenseObservation.Records(it) }
        override suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult {
            insertCalls++
            insertGate?.await()
            tokens += draft.operationToken
            savedDateTimes += draft.dateTime
            val record = ExpenseRecord(1, draft.amountPaise, draft.merchant, draft.dateTime, draft.category)
            records.value = listOf(record)
            return AddExpenseResult.Inserted(1)
        }
        override suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult {
            importCalls++
            importedDrafts += draft
            val record = ExpenseRecord(2, draft.amountPaise, draft.merchant, draft.dateTime, draft.category)
            if (importResult is AddExpenseResult.Inserted) records.value = records.value + record
            return importResult
        }
        override suspend fun deleteExpense(id: Long): Boolean {
            deleteCalls++
            records.value = records.value.filterNot { it.id == id }
            return true
        }
    }

    private class FixtureCategoryRepository(
        private var failFirstLoad: Boolean = false,
        private var failFirstAdd: Boolean = false,
    ) : CategoryRepository {
        var values = emptyList<String>()
        override suspend fun loadCustomCategories(): List<String> {
            if (failFirstLoad) { failFirstLoad = false; error("fixture load failure") }
            return values
        }
        override suspend fun addCustomCategory(name: String): List<String> {
            if (failFirstAdd) { failFirstAdd = false; error("fixture save failure") }
            val existing = values.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing == null) values = values + name
            return values
        }
    }
}
