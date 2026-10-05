package dev.snapbudget.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.snapbudget.domain.formatInr
import java.time.format.DateTimeFormatter

@Composable
fun ExpenseScreen(viewModel: ExpenseViewModel, onChooseTransactionImage: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != ExpensePage.LIST || state.deletingId != null) {
        when {
            state.committing -> Unit
            state.deletingId != null -> viewModel.dismissDelete()
            state.page == ExpensePage.REVIEW -> viewModel.editReview()
            else -> viewModel.cancelEditor()
        }
    }
    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp).then(if (state.page == ExpensePage.LIST) Modifier else Modifier.imePadding())
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.size(8.dp))
                when (state.page) {
                    ExpensePage.LIST -> ExpenseList(state, viewModel, onChooseTransactionImage)
                    ExpensePage.EDIT -> Editor(state, viewModel, onChooseTransactionImage)
                    ExpensePage.REVIEW -> Review(state, viewModel)
                }
                Text("Your expenses stay on this device. No accounts or uploads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("privacy-note").padding(bottom = 12.dp))
            }
        }
    }
    state.deletingId?.takeUnless { it == ExpenseUiState.MUTATION_IN_PROGRESS }?.let { id ->
        val record = state.expenses.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete expense?") },
            text = { Text("Remove ${record?.merchant ?: "this expense"} from your list?") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete, modifier = Modifier.testTag("confirm-delete")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete, modifier = Modifier.testTag("cancel-delete")) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExpenseList(state: ExpenseUiState, vm: ExpenseViewModel, onChooseTransactionImage: () -> Unit) {
    Text("SnapBudget", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("app-title"))
    Card(Modifier.fillMaxWidth().testTag("total-card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("All-time total", style = MaterialTheme.typography.titleMedium)
            Text(state.total, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("expense-total"))
            Text("${state.expenses.size} ${if (state.expenses.size == 1) "expense" else "expenses"}", modifier = Modifier.testTag("expense-count"))
        }
    }
    Button(onClick = vm::openEditor, enabled = !state.committing, modifier = Modifier.fillMaxWidth().testTag("add-expense")) { Text("Add expense") }
    OutlinedButton(onClick = onChooseTransactionImage, enabled = !state.committing, modifier = Modifier.fillMaxWidth().testTag("choose-image-list")) { Text("Choose transaction image") }
    state.errorMessage?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recoverable-error"))
        if (message.startsWith("Expenses could not be loaded")) TextButton(onClick = vm::retryLoading, modifier = Modifier.testTag("retry-loading")) { Text("Retry") }
    }
    when {
        state.loading -> CircularProgressIndicator(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).testTag("loading"))
        state.expenses.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No expenses yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("empty-state"))
            Text("Add your first expense to start tracking.")
        }
        else -> state.expenses.forEach { expense ->
            Card(Modifier.fillMaxWidth().testTag("expense-${expense.id}")) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(expense.merchant, style = MaterialTheme.typography.titleMedium)
                        Text("${expense.category} · ${expense.dateTime.format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatInr(expense.amountPaise), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { vm.requestDelete(expense.id) }, modifier = Modifier.testTag("delete-${expense.id}")) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Editor(state: ExpenseUiState, vm: ExpenseViewModel, onChooseTransactionImage: () -> Unit) {
    Text("Add expense", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("editor-title"))
    Text("Enter the details below. Nothing is saved until you confirm.", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = onChooseTransactionImage, enabled = !state.readingImage && !state.committing, modifier = Modifier.fillMaxWidth().testTag("choose-transaction-image")) { Text("Choose transaction image") }
    if (state.readingImage) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Reading receipt…", modifier = Modifier.testTag("reading-image"))
        TextButton(onClick = vm::onImageSelectionCancelled, modifier = Modifier.testTag("cancel-image-reading")) { Text("Cancel") }
    }
    if (state.sourceTransactionId != null) Text("Transaction ID: ${state.sourceTransactionId}", modifier = Modifier.testTag("transaction-identity"))
    EntryField("Merchant", state.merchant, vm::updateMerchant, state.errors["merchant"], tag = "merchant-input")
    EntryField("Amount in INR", state.amount, vm::updateAmount, state.errors["amount"], keyboard = KeyboardType.Decimal, tag = "amount-input", prefix = "₹")
    EntryField("Local date and time", state.dateTime, vm::updateDateTime, state.errors["dateTime"], tag = "datetime-input", supporting = "Format: YYYY-MM-DDTHH:MM (24-hour local time)")
    EntryField("Category", state.category, vm::updateCategory, state.errors["category"], tag = "category-input")
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recoverable-error")) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = vm::cancelEditor, enabled = !state.committing && !state.readingImage, modifier = Modifier.weight(1f).testTag("cancel-entry")) { Text("Cancel") }
        Button(onClick = vm::review, enabled = !state.committing && !state.readingImage, modifier = Modifier.weight(1f).testTag("review-expense")) { Text("Review") }
    }
}

@Composable
private fun EntryField(label: String, value: String, onValueChange: (String) -> Unit, error: String?, keyboard: KeyboardType = KeyboardType.Text, tag: String, prefix: String? = null, supporting: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth().testTag(tag),
            isError = error != null, singleLine = true, prefix = prefix?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            supportingText = { if (error != null) Text(error, modifier = Modifier.semantics { contentDescription = error }) else if (supporting != null) Text(supporting) },
        )
    }
}

@Composable
private fun Review(state: ExpenseUiState, vm: ExpenseViewModel) {
    val draft = state.draft
    val imported = state.importedDraft
    if (draft == null && imported == null) return
    Text("Review expense", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("review-title"))
    Text("Check the details before saving. This will only be saved after you confirm.")
    Card(Modifier.fillMaxWidth().testTag("review-details")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReviewValue("Merchant", draft?.merchant ?: imported!!.merchant)
            ReviewValue("Amount", formatInr(draft?.amountPaise ?: imported!!.amountPaise))
            ReviewValue("Date and time", (draft?.dateTime ?: imported!!.dateTime).format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")))
            ReviewValue("Category", draft?.category ?: imported!!.category)
            imported?.let {
                ReviewValue("Original image identity", it.imageHash)
                it.transactionId?.let { transactionId -> ReviewValue("Transaction ID", transactionId) }
            }
        }
    }
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recoverable-error")) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = vm::cancelEditor, enabled = !state.saving, modifier = Modifier.weight(1f).testTag("cancel-review")) { Text("Cancel") }
        OutlinedButton(onClick = vm::editReview, enabled = !state.saving && !state.duplicateImport, modifier = Modifier.weight(1f).testTag("edit-review")) { Text("Edit") }
        Button(onClick = vm::confirmSave, enabled = !state.saving && !state.duplicateImport, modifier = Modifier.weight(1f).testTag("confirm-save")) {
            if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Confirm save")
        }
    }
    if (state.duplicateImport) {
        TextButton(onClick = vm::cancelEditor, modifier = Modifier.testTag("return-after-duplicate")) { Text("Return to expenses") }
    }
}

@Composable
private fun ReviewValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("review-$label"))
    }
}
