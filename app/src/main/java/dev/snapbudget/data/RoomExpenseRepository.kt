package dev.snapbudget.data

import dev.snapbudget.Expense
import dev.snapbudget.ExpenseDao
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseEditDraft
import dev.snapbudget.domain.ExpenseRecord
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.domain.UpdateExpenseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Room adapter; manual-operation identity uses the unique imageHash index without inventing a receipt hash. */
class RoomExpenseRepository(private val dao: ExpenseDao) : ExpenseRepository, ImportedExpenseRepository {
    override fun observeExpenses(): Flow<ExpenseObservation> = dao.observe()
        .map<List<Expense>, ExpenseObservation> { records -> ExpenseObservation.Records(records.map { it.toDomain() }) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(ExpenseObservation.Failed)
        }

    override suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult = try {
        val id = dao.insert(
            Expense(
                amountPaise = draft.amountPaise,
                merchant = draft.merchant,
                dateTime = draft.dateTime.toString(),
                category = draft.category,
                transactionId = null,
                imageHash = draft.operationToken,
            ),
        )
        if (id > 0L) AddExpenseResult.Inserted(id) else AddExpenseResult.Duplicate
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AddExpenseResult.Failed
    }

    override suspend fun updateExpense(draft: ExpenseEditDraft): UpdateExpenseResult = try {
        when (dao.updateEditableFields(
            id = draft.id,
            amountPaise = draft.amountPaise,
            merchant = draft.merchant,
            dateTime = draft.dateTime.toString(),
            category = draft.category,
        )) {
            1 -> UpdateExpenseResult.Updated
            0 -> UpdateExpenseResult.Missing
            else -> UpdateExpenseResult.Failed
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        UpdateExpenseResult.Failed
    }

    override suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult = try {
        val id = dao.insert(
            Expense(
                amountPaise = draft.amountPaise,
                merchant = draft.merchant,
                dateTime = draft.dateTime.toString(),
                category = draft.category,
                transactionId = draft.transactionId,
                imageHash = draft.imageHash,
            ),
        )
        if (id > 0L) AddExpenseResult.Inserted(id) else AddExpenseResult.Duplicate
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AddExpenseResult.Failed
    }

    override suspend fun deleteExpense(id: Long): Boolean {
        if (id <= 0L) return false
        return try {
            dao.delete(id)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun Expense.toDomain() = ExpenseRecord(
        id = id,
        amountPaise = amountPaise,
        merchant = merchant,
        dateTime = java.time.LocalDateTime.parse(dateTime),
        category = category,
    )
}
