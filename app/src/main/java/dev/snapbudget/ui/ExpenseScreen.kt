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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Composable
fun ExpenseScreen(viewModel: ExpenseViewModel, onChooseTransactionImage: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != ExpensePage.LIST || state.deletingId != null) {
        when {
            state.committing -> Unit
            state.deletingId != null -> viewModel.dismissDelete()
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
    if (state.categoryDialogOpen) {
        AlertDialog(
            onDismissRequest = viewModel::closeCategoryDialog,
            title = { Text("Add category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.newCategoryName,
                        onValueChange = viewModel::updateNewCategoryName,
                        label = { Text("Category name") },
                        singleLine = true,
                        isError = state.categoryError != null,
                        enabled = !state.addingCategory,
                        modifier = Modifier.fillMaxWidth().testTag("new-category-input"),
                        supportingText = { state.categoryError?.let { Text(it, modifier = Modifier.testTag("category-error")) } },
                    )
                    if (!state.categoryCatalogLoaded) {
                        TextButton(onClick = viewModel::retryCategoryLoad, modifier = Modifier.testTag("retry-category-load")) { Text("Retry loading categories") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::addCategory, enabled = !state.addingCategory, modifier = Modifier.testTag("add-category-confirm")) {
                    if (state.addingCategory) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Add")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::closeCategoryDialog, enabled = !state.addingCategory, modifier = Modifier.testTag("add-category-cancel")) { Text("Cancel") } },
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
                        Text("${expense.category} · ${ExpenseDateTimeFormat.format(expense.dateTime)}", style = MaterialTheme.typography.bodySmall)
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
    Text("Enter the details below. Nothing is saved until you choose Save expense.", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = onChooseTransactionImage, enabled = !state.readingImage && !state.committing, modifier = Modifier.fillMaxWidth().testTag("choose-transaction-image")) { Text("Choose transaction image") }
    if (state.readingImage) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Reading receipt…", modifier = Modifier.testTag("reading-image"))
        TextButton(onClick = vm::onImageSelectionCancelled, modifier = Modifier.testTag("cancel-image-reading")) { Text("Cancel") }
    }
    if (state.sourceTransactionId != null) Text("Transaction ID: ${state.sourceTransactionId}", modifier = Modifier.testTag("transaction-identity"))
    EntryField("Merchant", state.merchant, vm::updateMerchant, state.errors["merchant"], tag = "merchant-input", enabled = !state.committing && !state.readingImage)
    EntryField("Amount in INR", state.amount, vm::updateAmount, state.errors["amount"], keyboard = KeyboardType.Decimal, tag = "amount-input", prefix = "₹", enabled = !state.committing && !state.readingImage)
    EntryField("Date and time", ExpenseDateTimeFormat.canonicalToReadable(state.dateTime), vm::updateDateTime, state.errors["dateTime"], tag = "datetime-input", supporting = "Example: ${ExpenseDateTimeFormat.EXAMPLE}", enabled = !state.committing && !state.readingImage)
    CategoryField(state, vm)
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recoverable-error")) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = vm::cancelEditor, enabled = !state.committing && !state.readingImage, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(if (state.duplicateImport) "return-after-duplicate" else "cancel-entry")) { Text(if (state.duplicateImport) "Return to expenses" else "Cancel") }
        Button(onClick = vm::saveExpense, enabled = !state.committing && !state.readingImage && !state.addingCategory && !state.duplicateImport && state.deletingId == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("save-expense")) {
            if (state.saving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Saving…")
            } else Text("Save expense")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(state: ExpenseUiState, vm: ExpenseViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val canInteract = !state.readingImage && !state.committing && !state.addingCategory
    val effectiveExpanded = expanded && canInteract
    val menuScrollState = rememberScrollState()
    LaunchedEffect(effectiveExpanded) {
        if (effectiveExpanded) menuScrollState.scrollTo(0)
    }
    ExposedDropdownMenuBox(expanded = effectiveExpanded, onExpandedChange = { if (canInteract) expanded = it }) {
        OutlinedTextField(
            value = state.category,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = effectiveExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = canInteract).testTag("category-input"),
            enabled = canInteract,
            isError = state.errors["category"] != null,
            supportingText = { state.errors["category"]?.let { Text(it, modifier = Modifier.testTag("category-validation-error")) } },
        )
        ExposedDropdownMenu(expanded = effectiveExpanded, onDismissRequest = { expanded = false }, scrollState = menuScrollState) {
            DropdownMenuItem(
                text = { Text("Add category…") },
                onClick = { expanded = false; vm.openCategoryDialog() },
                modifier = Modifier.testTag("add-category-option"),
            )
            HorizontalDivider()
            state.categoryOptions.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category) },
                    onClick = { vm.updateCategory(category); expanded = false },
                    modifier = Modifier.testTag("category-option-$category"),
                )
            }
        }
    }
}

@Composable
private fun EntryField(label: String, value: String, onValueChange: (String) -> Unit, error: String?, keyboard: KeyboardType = KeyboardType.Text, tag: String, prefix: String? = null, supporting: String? = null, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth().testTag(tag),
            isError = error != null, singleLine = true, prefix = prefix?.let { { Text(it) } }, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            supportingText = { if (error != null) Text(error, modifier = Modifier.semantics { contentDescription = error }) else if (supporting != null) Text(supporting) },
        )
    }
}
