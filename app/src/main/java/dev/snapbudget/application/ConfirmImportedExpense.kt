package dev.snapbudget.application

import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import kotlinx.coroutines.CancellationException

sealed interface ConfirmImportedExpenseResult {
    data object NotConfirmed : ConfirmImportedExpenseResult
    data class Inserted(val id: Long) : ConfirmImportedExpenseResult
    data object Duplicate : ConfirmImportedExpenseResult
    data object Failed : ConfirmImportedExpenseResult
}

/** Storage is never called before explicit user confirmation; failures carry no persistence details. */
class ConfirmImportedExpense(private val repository: ImportedExpenseRepository) {
    suspend operator fun invoke(draft: ImportedExpenseDraft, confirmed: Boolean): ConfirmImportedExpenseResult {
        if (!confirmed) return ConfirmImportedExpenseResult.NotConfirmed
        return try {
            when (val result = repository.addImportedExpense(draft)) {
                is AddExpenseResult.Inserted -> ConfirmImportedExpenseResult.Inserted(result.id)
                AddExpenseResult.Duplicate -> ConfirmImportedExpenseResult.Duplicate
                AddExpenseResult.Failed -> ConfirmImportedExpenseResult.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ConfirmImportedExpenseResult.Failed
        }
    }
}
