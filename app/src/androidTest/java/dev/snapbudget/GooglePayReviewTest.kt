package dev.snapbudget

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.snapbudget.application.ReceiptImagePreview
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageReader
import dev.snapbudget.application.ReceiptImageSelection
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseRecord
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.domain.ExpenseEditDraft
import dev.snapbudget.domain.UpdateExpenseResult
import dev.snapbudget.parsing.ReceiptParserFactory
import dev.snapbudget.ui.ExpenseScreen
import dev.snapbudget.ui.ExpenseViewModel
import dev.snapbudget.ui.SnapBudgetTheme
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Confirms Google Pay factory output stays an editable suggestion until the user taps Save expense. */
class GooglePayReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun factoryPreviewPrefillsEditorAndDirectSaveKeepsStableDuplicateIdentity() {
        val repository = FixtureRepository()
        val layout = ReceiptTextLayout(
            "Google Pay\nPayment of INR 123.45 completed\nCompleted\n3 May 2025, 8:22am\n" +
                "UPI transaction ID\n123456789012\nTo: Synthetic Shop\nFrom: Synthetic Sender\n" +
                "Google transaction ID\nGPA1234567890",
            emptyList(),
        )
        val parser = requireNotNull(ReceiptParserFactory.create(layout))
        val parsed = parser.parse(layout)
        assertTrue(parsed.eligible)
        val hash = MessageDigest.getInstance("SHA-256").digest("synthetic gpay fixture".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val reader = object : ReceiptImageReader {
            override suspend fun read(selection: ReceiptImageSelection) = ReceiptImageReadResult.Ready(
                ReceiptImagePreview(parsed.amountPaise, parsed.merchant, parsed.dateTime, hash, parsed.transactionId),
            )
        }
        val model = ExpenseViewModel(
            repository,
            receiptImageReader = reader,
            importedExpenseRepository = repository,
            savedState = androidx.lifecycle.SavedStateHandle(),
        )
        compose.setContent {
            SnapBudgetTheme {
                ExpenseScreen(model, onChooseTransactionImage = { model.openImageImport() })
            }
        }

        compose.onNodeWithTag("add-expense").performClick()
        compose.onNodeWithTag("choose-transaction-image").performScrollTo().performClick()
        model.onTransactionImageSelected(ReceiptImageSelection("synthetic-gpay-fixture"))
        compose.waitForIdle()
        compose.onNodeWithTag("merchant-input").performScrollTo().assertTextContains("Synthetic Shop")
        compose.onNodeWithTag("amount-input").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("123.45")),
        )
        compose.onNodeWithTag("datetime-input").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("3 May 2025, 8:22 AM")),
        )
        compose.onNodeWithTag("transaction-identity").performScrollTo()
            .assertTextEquals("Transaction ID: GPAY:UPI:123456789012")
        assertEquals("OCR prefill must not write", 0, repository.importCalls)

        compose.onNodeWithText(hash).assertDoesNotExist()
        compose.onNodeWithTag("merchant-input").assertTextContains("Synthetic Shop")
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("save-expense").performScrollTo().assertIsDisplayed().assertTextContains("Save expense")
        val saveBounds = save.fetchSemanticsNode().boundsInRoot
        assertTrue(saveBounds.width > 0f && saveBounds.height >= 48f * compose.density.density)
        assertTrue(saveBounds.left >= root.left && saveBounds.right <= root.right && saveBounds.bottom <= root.bottom)
        val saveImage = save.captureToImage()
        val savePixels = saveImage.toPixelMap()
        assertTrue(saveImage.width > 0 && saveImage.height > 0)
        assertTrue("Save action has visible rendered content", (0 until saveImage.width).any { x -> (0 until saveImage.height).any { y -> savePixels[x, y] != savePixels[0, 0] } })
        assertEquals(0, repository.importCalls)

        save.performClick()
        compose.waitForIdle()
        assertEquals(1, repository.importCalls)
        assertEquals(12_345L, repository.importedDrafts.single().amountPaise)
        assertEquals("Synthetic Shop", repository.importedDrafts.single().merchant)
        assertEquals(LocalDateTime.of(2025, 5, 3, 8, 22), repository.importedDrafts.single().dateTime)
        assertEquals(hash, repository.importedDrafts.single().imageHash)
        assertEquals("GPAY:UPI:123456789012", repository.importedDrafts.single().transactionId)

        repository.importResult = AddExpenseResult.Duplicate
        model.openImageImport()
        model.onTransactionImageSelected(ReceiptImageSelection("same-synthetic-gpay-fixture"))
        compose.waitForIdle()
        compose.onNodeWithTag("save-expense").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This receipt was already imported. No second expense was created.")
            .performScrollTo().assertExists()
        assertEquals(2, repository.importCalls)
        assertEquals(listOf("GPAY:UPI:123456789012", "GPAY:UPI:123456789012"),
            repository.importedDrafts.map(ImportedExpenseDraft::transactionId))
    }

    private class FixtureRepository : ExpenseRepository, ImportedExpenseRepository {
        private val records = MutableStateFlow<List<ExpenseRecord>>(emptyList())
        var importCalls = 0
        val importedDrafts = mutableListOf<ImportedExpenseDraft>()
        var importResult: AddExpenseResult = AddExpenseResult.Inserted(1)

        override fun observeExpenses(): Flow<ExpenseObservation> = records.asStateFlow()
            .map { ExpenseObservation.Records(it) }

        override suspend fun addManualExpense(draft: ManualExpenseDraft) = AddExpenseResult.Failed

        override suspend fun updateExpense(draft: ExpenseEditDraft): UpdateExpenseResult = UpdateExpenseResult.Failed

        override suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult {
            importCalls++
            importedDrafts += draft
            if (importResult is AddExpenseResult.Inserted) {
                records.value = records.value + ExpenseRecord(
                    id = importCalls.toLong(), amountPaise = draft.amountPaise, merchant = draft.merchant,
                    dateTime = draft.dateTime, category = draft.category,
                )
            }
            return importResult
        }

        override suspend fun deleteExpense(id: Long): Boolean = false
    }
}
