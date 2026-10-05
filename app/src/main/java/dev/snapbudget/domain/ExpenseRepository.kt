package dev.snapbudget.domain

import kotlinx.coroutines.flow.Flow

sealed interface ExpenseObservation {
    data class Records(val expenses: List<ExpenseRecord>) : ExpenseObservation
    /** Error details are intentionally not exposed to prevent accidental disclosure of storage internals. */
    data object Failed : ExpenseObservation
}

sealed interface AddExpenseResult {
    data class Inserted(val id: Long) : AddExpenseResult
    data object Duplicate : AddExpenseResult
    data object Failed : AddExpenseResult
}

/** Persistence boundary for local expense records. Implementations must preserve cancellation. */
interface ExpenseRepository {
    fun observeExpenses(): Flow<ExpenseObservation>
    suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult
    suspend fun deleteExpense(id: Long): Boolean
}
