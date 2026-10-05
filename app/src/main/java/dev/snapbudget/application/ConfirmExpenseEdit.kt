package dev.snapbudget.application

import dev.snapbudget.domain.ExpenseEditDraft
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.UpdateExpenseResult
import kotlinx.coroutines.CancellationException

sealed interface ConfirmExpenseEditResult {
    data object NotConfirmed : ConfirmExpenseEditResult
    data object Updated : ConfirmExpenseEditResult
    data object Missing : ConfirmExpenseEditResult
    data object Failed : ConfirmExpenseEditResult
}

/** Updates an expense only after explicit confirmation and hides persistence failure details. */
class ConfirmExpenseEdit(private val repository: ExpenseRepository) {
    suspend operator fun invoke(draft: ExpenseEditDraft, confirmed: Boolean): ConfirmExpenseEditResult {
        if (!confirmed) return ConfirmExpenseEditResult.NotConfirmed
        return try {
            when (repository.updateExpense(draft)) {
                UpdateExpenseResult.Updated -> ConfirmExpenseEditResult.Updated
                UpdateExpenseResult.Missing -> ConfirmExpenseEditResult.Missing
                UpdateExpenseResult.Failed -> ConfirmExpenseEditResult.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ConfirmExpenseEditResult.Failed
        }
    }
}
