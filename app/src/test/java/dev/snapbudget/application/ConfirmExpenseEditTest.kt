package dev.snapbudget.application

import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseEditDraft
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.domain.UpdateExpenseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfirmExpenseEditTest {
    private val draft = ExpenseEditDraft.create(7, "12.50", "Synthetic Cafe", "2025-05-03T21:41", "Other")!!

    @Test fun onlyCallsRepositoryAfterConfirmation() = runBlocking {
        val repository = FakeRepository()
        assertEquals(ConfirmExpenseEditResult.NotConfirmed, ConfirmExpenseEdit(repository)(draft, false))
        assertEquals(0, repository.updateCalls)
        assertEquals(ConfirmExpenseEditResult.Updated, ConfirmExpenseEdit(repository)(draft, true))
        assertEquals(1, repository.updateCalls)
    }

    @Test fun mapsMissingAndFailedResultsAndSanitizesExceptions() = runBlocking {
        val repository = FakeRepository()
        val action = ConfirmExpenseEdit(repository)
        repository.result = UpdateExpenseResult.Missing
        assertEquals(ConfirmExpenseEditResult.Missing, action(draft, true))
        repository.result = UpdateExpenseResult.Failed
        assertEquals(ConfirmExpenseEditResult.Failed, action(draft, true))
        repository.thrown = IllegalStateException("private database detail")
        assertEquals(ConfirmExpenseEditResult.Failed, action(draft, true))
    }

    @Test fun cancellationPropagates() {
        val repository = FakeRepository().apply { thrown = CancellationException("cancel") }
        assertThrows(CancellationException::class.java) {
            runBlocking { ConfirmExpenseEdit(repository)(draft, true) }
        }
    }

    private class FakeRepository : ExpenseRepository {
        var updateCalls = 0
        var result: UpdateExpenseResult = UpdateExpenseResult.Updated
        var thrown: Exception? = null
        override fun observeExpenses(): Flow<ExpenseObservation> = emptyFlow()
        override suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult = AddExpenseResult.Failed
        override suspend fun updateExpense(draft: ExpenseEditDraft): UpdateExpenseResult {
            updateCalls++
            thrown?.let { throw it }
            return result
        }
        override suspend fun deleteExpense(id: Long): Boolean = false
    }
}
