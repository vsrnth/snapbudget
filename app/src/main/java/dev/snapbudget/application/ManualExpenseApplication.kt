package dev.snapbudget.application

import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import kotlinx.coroutines.CancellationException

sealed interface ConfirmManualExpenseResult {
    data object NotConfirmed : ConfirmManualExpenseResult
    data class Inserted(val id: Long) : ConfirmManualExpenseResult
    data object Duplicate : ConfirmManualExpenseResult
    data object Failed : ConfirmManualExpenseResult
}

/** Application boundary used by UI: persistence is never called until explicit confirmation. */
class ConfirmManualExpense(private val repository: ExpenseRepository) {
    suspend operator fun invoke(draft: ManualExpenseDraft, confirmed: Boolean): ConfirmManualExpenseResult {
        if (!confirmed) return ConfirmManualExpenseResult.NotConfirmed
        return try {
            when (val result = repository.addManualExpense(draft)) {
                is AddExpenseResult.Inserted -> ConfirmManualExpenseResult.Inserted(result.id)
                AddExpenseResult.Duplicate -> ConfirmManualExpenseResult.Duplicate
                AddExpenseResult.Failed -> ConfirmManualExpenseResult.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ConfirmManualExpenseResult.Failed
        }
    }
}
