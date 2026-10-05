package dev.snapbudget.domain

/** Persistence boundary for confirmed receipt imports, separate from manual-entry identity semantics. */
interface ImportedExpenseRepository {
    suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult
}
