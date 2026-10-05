package dev.snapbudget.application

import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseEditDraft
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

class ConfirmManualExpenseTest {
    private val draft = ManualExpenseDraft.createWithToken("12.50", "Synthetic Cafe", "2025-05-03T21:41", "Other", "manual:retry")!!

    @Test fun doesNotSaveUntilConfirmed() = runBlocking {
        val repository = FakeRepository()
        assertEquals(ConfirmManualExpenseResult.NotConfirmed, ConfirmManualExpense(repository)(draft, false))
        assertEquals(0, repository.addCalls)
    }

    @Test fun retryUsesSameDraftIdentityAndDuplicateIsReported() = runBlocking {
        val repository = FakeRepository().apply { results.add(AddExpenseResult.Inserted(7)); results.add(AddExpenseResult.Duplicate) }
        val action = ConfirmManualExpense(repository)
        assertEquals(ConfirmManualExpenseResult.Inserted(7), action(draft, true))
        assertEquals(ConfirmManualExpenseResult.Duplicate, action(draft, true))
        assertEquals(listOf("manual:retry", "manual:retry"), repository.tokens)
    }

    @Test fun repositoryErrorsAreSanitizedAndCancellationPropagates() = runBlocking {
        val failed = FakeRepository().apply { thrown = IllegalStateException("sensitive store details") }
        assertEquals(ConfirmManualExpenseResult.Failed, ConfirmManualExpense(failed)(draft, true))
        val cancelled = FakeRepository().apply { thrown = CancellationException("cancel") }
        assertThrows(CancellationException::class.java) {
            runBlocking { ConfirmManualExpense(cancelled)(draft, true) }
        }
        Unit
    }

    private class FakeRepository : ExpenseRepository {
        var addCalls = 0
        var thrown: Exception? = null
        val results = ArrayDeque<AddExpenseResult>()
        val tokens = mutableListOf<String>()

        override fun observeExpenses(): Flow<ExpenseObservation> = emptyFlow()
        override suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult {
            addCalls++
            tokens += draft.operationToken
            thrown?.let { throw it }
            return if (results.isEmpty()) AddExpenseResult.Failed else results.removeFirst()
        }
        override suspend fun updateExpense(draft: ExpenseEditDraft): UpdateExpenseResult = UpdateExpenseResult.Missing
        override suspend fun deleteExpense(id: Long): Boolean = id > 0
    }
}
