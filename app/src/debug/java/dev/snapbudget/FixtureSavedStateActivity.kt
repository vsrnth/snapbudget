package dev.snapbudget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageReader
import dev.snapbudget.application.ReceiptImageSelection
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.ui.ExpenseViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Debug-only lifecycle host backed by in-memory fixtures, never the production database. */
class FixtureSavedStateActivity : ComponentActivity() {
    lateinit var expenseViewModel: ExpenseViewModel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = FixtureExpenseRepository()
        expenseViewModel = ViewModelProvider(
            this,
            expenseViewModelFactory(repository, NoReceiptImageReader, repository),
        )[ExpenseViewModel::class.java]
    }
}

private object NoReceiptImageReader : ReceiptImageReader {
    override suspend fun read(selection: ReceiptImageSelection): ReceiptImageReadResult = ReceiptImageReadResult.Unsupported
}

private class FixtureExpenseRepository : ExpenseRepository, ImportedExpenseRepository {
    override fun observeExpenses(): Flow<ExpenseObservation> = flowOf(ExpenseObservation.Records(emptyList()))
    override suspend fun addManualExpense(draft: ManualExpenseDraft): AddExpenseResult = AddExpenseResult.Inserted(1L)
    override suspend fun addImportedExpense(draft: ImportedExpenseDraft): AddExpenseResult = AddExpenseResult.Inserted(1L)
    override suspend fun deleteExpense(id: Long): Boolean = true
}
