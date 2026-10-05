package dev.snapbudget.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.snapbudget.application.ConfirmImportedExpense
import dev.snapbudget.application.ConfirmImportedExpenseResult
import dev.snapbudget.application.ConfirmManualExpense
import dev.snapbudget.application.ConfirmManualExpenseResult
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageReader
import dev.snapbudget.application.ReceiptImageSelection
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseRecord
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.CategoryCatalog
import dev.snapbudget.domain.CategoryRepository
import dev.snapbudget.domain.InMemoryCategoryRepository
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.domain.ManualExpenseDraft
import dev.snapbudget.domain.formatInr
import dev.snapbudget.domain.parsePositivePaise
import dev.snapbudget.domain.totalPaise
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val strictLocalDateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm")
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

enum class ExpensePage { LIST, EDIT }

data class ExpenseUiState(
    val expenses: List<ExpenseRecord> = emptyList(),
    val page: ExpensePage = ExpensePage.LIST,
    val merchant: String = "",
    val amount: String = "",
    val dateTime: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")),
    val category: String = "Other",
    val errors: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val deletingId: Long? = null,
    val errorMessage: String? = null,
    val sourceImageHash: String? = null,
    val sourceTransactionId: String? = null,
    val readingImage: Boolean = false,
    val duplicateImport: Boolean = false,
    val categoryOptions: List<String> = CategoryCatalog.predefined,
    val categoryDialogOpen: Boolean = false,
    val newCategoryName: String = "",
    val categoryError: String? = null,
    val addingCategory: Boolean = false,
    val categoryCatalogLoaded: Boolean = false,
) {
    val total: String get() = formatInr(totalPaise(expenses))
    val committing: Boolean get() = saving || deletingId == MUTATION_IN_PROGRESS
    companion object { const val MUTATION_IN_PROGRESS = Long.MIN_VALUE }
}

/** UI operations over local expense and receipt-reading boundaries. */
class ExpenseViewModel(
    private val repository: ExpenseRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val receiptImageReader: ReceiptImageReader? = null,
    private val importedExpenseRepository: ImportedExpenseRepository? = null,
    private val categoryRepository: CategoryRepository = InMemoryCategoryRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<ExpenseUiState> = _state.asStateFlow()
    private var observationJob: Job? = null
    private var imageReadJob: Job? = null
    private var imageReadGeneration = 0
    private var customCategories: List<String> = emptyList()
    private var categoryCatalogLoaded = false
    private var categoryLoadJob: Job? = null

    init {
        refreshCategoryOptions()
        // A URI selection is ephemeral and must never be resumed after process recreation.
        if (_state.value.readingImage) _state.update { it.copy(readingImage = false, errorMessage = "Select the receipt image again to continue.") }
        observeExpenses()
        categoryLoadJob = viewModelScope.launch { loadCategoryCatalog() }
    }

    private fun observeExpenses() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            repository.observeExpenses().catch {
                _state.update { it.copy(loading = false, errorMessage = "Expenses could not be loaded. Please try again.") }
            }.collect { result ->
                when (result) {
                    is ExpenseObservation.Records -> {
                        _state.update { it.copy(expenses = result.expenses, loading = false, errorMessage = it.errorMessage?.takeUnless { message -> message.startsWith("Expenses could not be loaded") }) }
                        refreshCategoryOptions()
                    }
                    ExpenseObservation.Failed -> _state.update { it.copy(loading = false, errorMessage = "Expenses could not be loaded. Please try again.") }
                }
            }
        }
    }

    fun retryLoading() {
        _state.update { it.copy(loading = true, errorMessage = null) }
        observeExpenses()
    }

    fun openEditor() {
        if (_state.value.committing || _state.value.addingCategory) return
        _state.update { it.copy(page = ExpensePage.EDIT, errors = emptyMap(), errorMessage = null, sourceImageHash = null, sourceTransactionId = null, duplicateImport = false, categoryDialogOpen = false) }
        savedState.remove<String>("importImageHash")
        savedState.remove<String>("importTransactionId")
        savedState["readingImage"] = false
        persist()
    }

    fun openImageImport() {
        if (_state.value.committing || _state.value.readingImage || _state.value.addingCategory) return
        if (receiptImageReader == null || importedExpenseRepository == null) {
            _state.update { it.copy(errorMessage = "Receipt import is unavailable. Please try again later.") }
            return
        }
        _state.update { it.copy(errors = emptyMap(), errorMessage = null) }
    }

    fun onImageSelectionCancelled() {
        imageReadGeneration++
        imageReadJob?.cancel()
        imageReadJob = null
        _state.update { it.copy(readingImage = false) }
        savedState["readingImage"] = false
    }

    /** Called by the Activity's image-picker result callback; selection tokens are never persisted. */
    fun onTransactionImageSelected(selection: ReceiptImageSelection) {
        val reader = receiptImageReader ?: return
        if (_state.value.committing || _state.value.addingCategory) return
        imageReadGeneration++
        val generation = imageReadGeneration
        imageReadJob?.cancel()
        _state.update { it.copy(
            page = ExpensePage.EDIT, readingImage = true, errors = emptyMap(), errorMessage = null,
            sourceImageHash = null, sourceTransactionId = null,
            merchant = "", amount = "", dateTime = "", category = "Other", duplicateImport = false,
        ) }
        savedState["merchant"] = ""
        savedState["amount"] = ""
        savedState["dateTime"] = ""
        savedState["category"] = "Other"
        savedState.remove<String>("operationToken")
        savedState.remove<String>("importImageHash")
        savedState.remove<String>("importTransactionId")
        savedState["page"] = ExpensePage.EDIT.name
        savedState["readingImage"] = true
        imageReadJob = viewModelScope.launch {
            try {
                when (val result = reader.read(selection)) {
                    is ReceiptImageReadResult.Ready -> {
                        val preview = result.preview
                        if (generation != imageReadGeneration) return@launch
                        _state.update { it.copy(
                            readingImage = false,
                            merchant = preview.merchant.orEmpty(),
                            amount = preview.amountPaise?.let { paise -> "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}" }.orEmpty(),
                            dateTime = preview.dateTime?.format(strictLocalDateTimeFormatter).orEmpty(),
                            category = "Other",
                            page = ExpensePage.EDIT,
                            sourceImageHash = preview.imageHash,
                            sourceTransactionId = preview.transactionId,
                            errors = emptyMap(), errorMessage = null,
                        ) }
                        // An incomplete preview remains editable but is validated before saving.
                        savedState["merchant"] = preview.merchant.orEmpty()
                        savedState["amount"] = preview.amountPaise?.let { paise -> "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}" }.orEmpty()
                        savedState["dateTime"] = preview.dateTime?.format(strictLocalDateTimeFormatter).orEmpty()
                        savedState["category"] = "Other"
                        savedState["readingImage"] = false
                        persistImportState(preview.imageHash, preview.transactionId)
                    }
                    ReceiptImageReadResult.InvalidSelection -> showReadError(generation, "That image selection is unavailable. Select another image.")
                    ReceiptImageReadResult.Unreadable -> showReadError(generation, "The image could not be read. Select another image.")
                    ReceiptImageReadResult.Unsupported -> showReadError(generation, "This image is not a supported receipt. Select another image.")
                    ReceiptImageReadResult.Failed -> showReadError(generation, "The receipt could not be read. Select another image.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showReadError(generation, "The receipt could not be read. Select another image.")
            } finally {
                if (generation == imageReadGeneration) _state.update { it.copy(readingImage = false) }
            }
        }
    }

    private fun showReadError(generation: Int, message: String) {
        if (generation == imageReadGeneration) _state.update { it.copy(readingImage = false, errorMessage = message) }
    }

    fun updateMerchant(value: String) = updateField("merchant", value) { copy(merchant = value) }
    fun updateAmount(value: String) = updateField("amount", value) { copy(amount = value) }
    fun updateDateTime(value: String) {
        val canonical = ExpenseDateTimeFormat.inputToCanonical(value)
        updateField("dateTime", canonical ?: value) { copy(dateTime = canonical ?: value) }
    }
    fun updateCategory(value: String) {
        val canonical = CategoryCatalog.existingName(value, _state.value.categoryOptions) ?: return
        updateField("category", canonical) { copy(category = canonical) }
    }

    fun openCategoryDialog() {
        if (_state.value.page != ExpensePage.EDIT || _state.value.readingImage || _state.value.committing || _state.value.addingCategory) return
        _state.update {
            it.copy(
                categoryDialogOpen = true,
                newCategoryName = "",
                categoryError = when {
                    categoryCatalogLoaded -> null
                    categoryLoadJob?.isActive == true -> "Saved categories are loading."
                    else -> "Saved categories could not be loaded."
                },
            )
        }
    }

    fun retryCategoryLoad() {
        if (_state.value.addingCategory || categoryCatalogLoaded || categoryLoadJob?.isActive == true) return
        categoryLoadJob = viewModelScope.launch { loadCategoryCatalog(showError = true) }
    }

    private suspend fun loadCategoryCatalog(showError: Boolean = false): Boolean {
        return try {
            customCategories = categoryRepository.loadCustomCategories()
            categoryCatalogLoaded = true
            _state.update { it.copy(categoryCatalogLoaded = true, categoryError = if (showError) null else it.categoryError) }
            refreshCategoryOptions()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            categoryCatalogLoaded = false
            _state.update { it.copy(categoryCatalogLoaded = false, categoryError = if (showError) "Saved categories could not be loaded. Please retry." else it.categoryError) }
            false
        }
    }

    fun updateNewCategoryName(value: String) {
        if (_state.value.addingCategory) return
        _state.update { it.copy(newCategoryName = value, categoryError = null) }
    }

    fun closeCategoryDialog() {
        if (_state.value.addingCategory) return
        _state.update { it.copy(categoryDialogOpen = false, newCategoryName = "", categoryError = null) }
    }

    fun addCategory() {
        val current = _state.value
        if (!current.categoryDialogOpen || current.page != ExpensePage.EDIT || current.addingCategory || current.readingImage || current.committing) return
        val error = CategoryCatalog.validationError(current.newCategoryName)
        if (error != null) { _state.update { it.copy(categoryError = error) }; return }
        val name = CategoryCatalog.normalizeName(current.newCategoryName)
        val existing = CategoryCatalog.existingName(name, current.categoryOptions)
        if (CategoryCatalog.existingName(name, CategoryCatalog.predefined) != null) {
            selectCategory(existing ?: name)
            closeCategoryDialog()
            return
        }
        _state.update { it.copy(addingCategory = true, categoryError = null) }
        viewModelScope.launch {
            try {
                if (!categoryCatalogLoaded) {
                    categoryLoadJob?.join()
                    if (!categoryCatalogLoaded && !loadCategoryCatalog(showError = true)) {
                        _state.update { it.copy(addingCategory = false, categoryError = "Saved categories could not be loaded. Please retry.") }
                        return@launch
                    }
                }
                val canonical = CategoryCatalog.existingName(name, _state.value.categoryOptions) ?: name
                val updated = categoryRepository.addCustomCategory(canonical)
                customCategories = updated
                refreshCategoryOptions()
                if (_state.value.page == ExpensePage.EDIT && _state.value.categoryDialogOpen) {
                    selectCategory(CategoryCatalog.existingName(name, _state.value.categoryOptions) ?: name)
                    _state.update { it.copy(categoryDialogOpen = false, newCategoryName = "", categoryError = null, addingCategory = false) }
                } else _state.update { it.copy(addingCategory = false, categoryDialogOpen = false) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(addingCategory = false) }
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(addingCategory = false, categoryError = "Category could not be saved. Please try again.") }
            }
        }
    }

    private fun selectCategory(value: String) {
        updateField("category", value) { copy(category = value) }
    }

    private fun refreshCategoryOptions() {
        _state.update { state -> state.copy(categoryOptions = CategoryCatalog.choices(customCategories, state.expenses.map { it.category }, state.category)) }
    }

    private fun updateField(key: String, value: String, change: ExpenseUiState.() -> ExpenseUiState) {
        if (_state.value.committing || _state.value.readingImage) return
        _state.update { it.change().copy(errors = it.errors - key, errorMessage = null, duplicateImport = false) }
        savedState[key] = value
        savedState["operationToken"] = null
        _state.value.sourceImageHash?.let { persistImportState(it, _state.value.sourceTransactionId) }
    }

    fun saveExpense() {
        val s = _state.value
        if (s.page != ExpensePage.EDIT || s.committing || s.readingImage || s.addingCategory || s.duplicateImport || s.deletingId != null) return
        val importedDraft = s.sourceImageHash?.let { hash ->
            ImportedExpenseDraft.create(
                parsePositivePaise(s.amount) ?: 0L, s.merchant, s.dateTime, s.category,
                hash, s.sourceTransactionId,
            )
        }
        val manualDraft = if (s.sourceImageHash == null) {
            val token = savedState.get<String>("operationToken") ?: "manual:${UUID.randomUUID()}".also { savedState["operationToken"] = it }
            ManualExpenseDraft.createWithToken(s.amount, s.merchant, s.dateTime, s.category, token)
        } else null
        if (manualDraft == null && importedDraft == null) {
            val errors = buildMap {
                if (s.merchant.trim().isEmpty() || s.merchant.length > 120) put("merchant", "Enter a merchant name (up to 120 characters).")
                if (parsePositivePaise(s.amount) == null) put("amount", "Enter an amount greater than ₹0, with up to two decimal places.")
                if (!ExpenseDateTimeFormat.isValidInput(s.dateTime)) put("dateTime", "Enter a valid date and time, for example ${ExpenseDateTimeFormat.EXAMPLE}.")
                if (s.category.trim().isEmpty() || s.category.length > 60) put("category", "Enter a category (up to 60 characters).")
            }
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true, errors = emptyMap(), errorMessage = null) }
        viewModelScope.launch {
            try {
                if (importedDraft != null) {
                    val importRepository = importedExpenseRepository
                    val result = if (importRepository == null) ConfirmImportedExpenseResult.Failed else ConfirmImportedExpense(importRepository)(importedDraft, confirmed = true)
                    when (result) {
                        is ConfirmImportedExpenseResult.Inserted -> clearAfterSave()
                        ConfirmImportedExpenseResult.Duplicate -> _state.update { it.copy(saving = false, duplicateImport = true, errorMessage = "This receipt was already imported. No second expense was created.") }
                        ConfirmImportedExpenseResult.Failed, ConfirmImportedExpenseResult.NotConfirmed -> _state.update { it.copy(saving = false, errorMessage = "Expense could not be saved. Retry to try again.") }
                    }
                } else when (ConfirmManualExpense(repository)(requireNotNull(manualDraft), confirmed = true)) {
                    is ConfirmManualExpenseResult.Inserted -> clearAfterSave()
                    ConfirmManualExpenseResult.Duplicate -> _state.update { it.copy(saving = false, errorMessage = "This expense was already saved. You can safely retry or return to your expenses.") }
                    ConfirmManualExpenseResult.Failed, ConfirmManualExpenseResult.NotConfirmed -> _state.update { it.copy(saving = false, errorMessage = "Expense could not be saved. Please try again.") }
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(saving = false) }
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, errorMessage = "Expense could not be saved. Please try again.") }
            }
        }
    }

    private fun clearAfterSave() {
        savedState["operationToken"] = null
        _state.update { it.copy(saving = false, page = ExpensePage.LIST, sourceImageHash = null, sourceTransactionId = null, merchant = "", amount = "", category = "Other", dateTime = now(), errorMessage = null, duplicateImport = false) }
        clearSavedForm()
    }

    fun cancelEditor() {
        if (_state.value.committing || _state.value.addingCategory) return
        imageReadGeneration++
        imageReadJob?.cancel()
        imageReadJob = null
        _state.update { it.copy(page = ExpensePage.LIST, merchant = "", amount = "", dateTime = now(), category = "Other", errors = emptyMap(), errorMessage = null, sourceImageHash = null, sourceTransactionId = null, saving = false, readingImage = false, duplicateImport = false, categoryDialogOpen = false) }
        clearSavedForm()
    }

    fun requestDelete(id: Long) {
        if (_state.value.committing) return
        _state.update { it.copy(deletingId = id, errorMessage = null) }
    }
    fun dismissDelete() { if (!_state.value.committing) _state.update { it.copy(deletingId = null) } }
    fun confirmDelete() {
        val id = _state.value.deletingId ?: return
        if (_state.value.committing) return
        _state.update { it.copy(deletingId = ExpenseUiState.MUTATION_IN_PROGRESS, errorMessage = null) }
        viewModelScope.launch {
            try {
                val deleted = repository.deleteExpense(id)
                _state.update { it.copy(deletingId = null, errorMessage = if (deleted) null else "Expense could not be deleted. Please try again.") }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(deletingId = null) }
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(deletingId = null, errorMessage = "Expense could not be deleted. Please try again.") }
            }
        }
    }

    fun dismissError() { _state.update { it.copy(errorMessage = null) } }

    private fun persistImportState(hash: String, transactionId: String?) {
        savedState["importImageHash"] = hash
        savedState["importTransactionId"] = transactionId
    }
    private fun now() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"))
    private fun clearSavedForm() {
        listOf("merchant", "amount", "dateTime", "category", "operationToken", "page", "importImageHash", "importTransactionId", "readingImage").forEach { savedState.remove<Any>(it) }
    }
    private fun persist() {
        savedState["page"] = _state.value.page.name
        savedState["readingImage"] = _state.value.readingImage
    }
    private fun restoreState(): ExpenseUiState {
        val rawPage = savedState.get<String>("page")
        val page = if (rawPage == "REVIEW") ExpensePage.EDIT else rawPage?.let { runCatching { ExpensePage.valueOf(it) }.getOrNull() } ?: ExpensePage.LIST
        if (rawPage == "REVIEW") savedState["page"] = ExpensePage.EDIT.name
        val merchant = savedState["merchant"] ?: ""
        val amount = savedState["amount"] ?: ""
        val dateTime = savedState["dateTime"] ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"))
        val category = savedState["category"] ?: "Other"
        val importHash = savedState.get<String>("importImageHash")
        val reading = savedState.get<Boolean>("readingImage") == true
        return ExpenseUiState(
            page = if (reading) ExpensePage.EDIT else page,
            merchant = merchant, amount = amount, dateTime = dateTime, category = category,
            sourceImageHash = importHash,
            sourceTransactionId = savedState.get("importTransactionId"), readingImage = false,
            errorMessage = if (reading) "Select the receipt image again to continue." else null,
        )
    }
}
