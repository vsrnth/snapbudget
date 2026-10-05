package dev.snapbudget.application

import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfirmImportedExpenseTest {
    @Test
    fun doesNotCallRepositoryBeforeExplicitConfirmation() = runBlocking {
        val repository = RecordingRepository()
        val draft = syntheticDraft()

        val result = ConfirmImportedExpense(repository)(draft, confirmed = false)

        assertEquals(ConfirmImportedExpenseResult.NotConfirmed, result)
        assertEquals(0, repository.calls)
        assertEquals(null, repository.receivedDraft)
    }

    @Test
    fun mapsConfirmedRepositoryResultsAndPassesTheSameIdentityBearingDraft() = runBlocking {
        val draft = syntheticDraft()
        val results = listOf(
            AddExpenseResult.Inserted(42L) to ConfirmImportedExpenseResult.Inserted(42L),
            AddExpenseResult.Duplicate to ConfirmImportedExpenseResult.Duplicate,
            AddExpenseResult.Failed to ConfirmImportedExpenseResult.Failed,
        )

        results.forEach { (repositoryResult, expected) ->
            val repository = RecordingRepository(result = repositoryResult)

            assertEquals(expected, ConfirmImportedExpense(repository)(draft, confirmed = true))
            assertEquals(1, repository.calls)
            assertSame(draft, repository.receivedDraft)
            assertEquals(sha256(), repository.receivedDraft?.imageHash)
            assertEquals("T123456789012345678", repository.receivedDraft?.transactionId)
        }
    }

    @Test
    fun repositoryExceptionMapsToFailed() = runBlocking {
        val repository = RecordingRepository(failure = IllegalStateException("synthetic failure"))

        val result = ConfirmImportedExpense(repository)(syntheticDraft(), confirmed = true)

        assertEquals(ConfirmImportedExpenseResult.Failed, result)
        assertEquals(1, repository.calls)
    }

    @Test
    fun cancellationIsRethrownWithoutBeingMappedToFailure() {
        val repository = RecordingRepository(failure = CancellationException("synthetic cancellation"))
        val confirm = ConfirmImportedExpense(repository)
        val draft = syntheticDraft()

        assertThrows(CancellationException::class.java) {
            runBlocking { confirm(draft, confirmed = true) }
        }
        assertEquals(1, repository.calls)
        assertSame(draft, repository.receivedDraft)
    }

    private class RecordingRepository(
        private val result: AddExpenseResult = AddExpenseResult.Failed,
        private val failure: Exception? = null,
    ) : ImportedExpenseRepository {
        var calls: Int = 0
        var receivedDraft: ImportedExpenseDraft? = null

        override suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult {
            calls++
            receivedDraft = draft
            failure?.let { throw it }
            return result
        }
    }

    private fun syntheticDraft(): ImportedExpenseDraft {
        val draft = ImportedExpenseDraft.create(
            amountPaise = 12_550L,
            merchant = "Synthetic Cafe",
            dateTime = "2025-05-03T21:41",
            category = "Food",
            imageHash = sha256(),
            transactionId = "T123456789012345678",
        )
        assertNotNull(draft)
        return checkNotNull(draft)
    }

    private fun sha256() = "ab".repeat(32)
}
