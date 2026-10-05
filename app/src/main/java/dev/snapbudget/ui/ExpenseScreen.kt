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
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import dev.snapbudget.domain.monthlyExpenses
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ExpenseScreen(viewModel: ExpenseViewModel, onChooseTransactionImage: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshMonth() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen || state.page != ExpensePage.LIST || state.deletingId != null) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            state.committing -> Unit
            state.deletingId != null -> viewModel.dismissDelete()
            state.page == ExpensePage.MONTH -> viewModel.showAllExpenses()
            else -> viewModel.cancelEditor()
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.page != ExpensePage.EDIT && !state.committing && state.deletingId == null,
        drawerContent = {
            ModalDrawerSheet {
                Text("SnapBudget", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                NavigationDrawerItem(
                    label = { Text("All expenses") },
                    selected = state.page == ExpensePage.LIST,
                    onClick = {
                        viewModel.showAllExpenses()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.testTag("destination-all"),
                )
                NavigationDrawerItem(
                    label = { Text("This month") },
                    selected = state.page == ExpensePage.MONTH,
                    onClick = {
                        viewModel.openMonth()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.testTag("destination-month"),
                )
            }
        },
    ) {
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
                        ExpensePage.LIST -> ExpenseList(state, viewModel, onChooseTransactionImage, monthly = false, menuEnabled = !state.committing && state.deletingId == null, onOpenMenu = { scope.launch { drawerState.open() } })
                        ExpensePage.MONTH -> ExpenseList(state, viewModel, onChooseTransactionImage, monthly = true, menuEnabled = !state.committing && state.deletingId == null, onOpenMenu = { scope.launch { drawerState.open() } })
                        ExpensePage.EDIT -> Editor(state, viewModel, onChooseTransactionImage)
                    }
                    Text("Your expenses stay on this device. No accounts or uploads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("privacy-note").padding(bottom = 12.dp))
                }
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
private fun ExpenseList(state: ExpenseUiState, vm: ExpenseViewModel, onChooseTransactionImage: () -> Unit, monthly: Boolean, menuEnabled: Boolean, onOpenMenu: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onOpenMenu, enabled = menuEnabled, modifier = Modifier.size(48.dp).testTag("open-navigation").semantics { contentDescription = "Open navigation" }) { Text("☰") }
        Text(if (monthly) state.month.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.getDefault())) else "SnapBudget", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(if (monthly) "month-title" else "app-title"))
    }
    val monthSummary = if (monthly) monthlyExpenses(state.expenses, state.month) else null
    val visibleExpenses = monthSummary?.records ?: state.expenses
    val visibleTotal = monthSummary?.let { formatInr(it.totalPaise) } ?: state.total
    val visibleCount = visibleExpenses.size
    Card(Modifier.fillMaxWidth().testTag("total-card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (monthly) "This month" else "All-time total", style = MaterialTheme.typography.titleMedium)
            Text(visibleTotal, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(if (monthly) "month-total" else "expense-total"))
            Text("$visibleCount ${if (visibleCount == 1) "expense" else "expenses"}", modifier = Modifier.testTag(if (monthly) "month-count" else "expense-count"))
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
        visibleExpenses.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (monthly) "No expenses this month" else "No expenses yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(if (monthly) "month-empty-state" else "empty-state"))
            Text(if (monthly) "Expenses dated in this month will appear here." else "Add your first expense to start tracking.")
        }
        else -> visibleExpenses.forEach { expense ->
            Card(Modifier.fillMaxWidth().testTag("expense-${expense.id}")) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(expense.merchant, style = MaterialTheme.typography.titleMedium)
                        Text("${expense.category} · ${ExpenseDateTimeFormat.format(expense.dateTime)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatInr(expense.amountPaise), fontWeight = FontWeight.SemiBold)
                        Row {
                            TextButton(onClick = { vm.editExpense(expense.id) }, modifier = Modifier.testTag("edit-${expense.id}")) { Text("Edit") }
                            TextButton(onClick = { vm.requestDelete(expense.id) }, modifier = Modifier.testTag("delete-${expense.id}")) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Editor(state: ExpenseUiState, vm: ExpenseViewModel, onChooseTransactionImage: () -> Unit) {
    Text(if (state.editingId == null) "Add expense" else "Edit expense", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(if (state.editingId == null) "editor-title" else "edit-editor-title"))
    Text(if (state.editingId == null) "Enter the details below. Nothing is saved until you choose Save expense." else "Update the fields, then choose Save changes.", style = MaterialTheme.typography.bodyMedium)
    if (state.editingId == null) OutlinedButton(onClick = onChooseTransactionImage, enabled = !state.readingImage && !state.committing, modifier = Modifier.fillMaxWidth().testTag("choose-transaction-image")) { Text("Choose transaction image") }
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
        Button(onClick = vm::saveExpense, enabled = !state.committing && !state.readingImage && !state.addingCategory && !state.duplicateImport && state.deletingId == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(if (state.editingId == null) "save-expense" else "save-edit")) {
            if (state.saving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Saving…")
            } else Text(if (state.editingId == null) "Save expense" else "Save changes")
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
