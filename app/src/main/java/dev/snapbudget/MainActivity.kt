package dev.snapbudget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import dev.snapbudget.application.ReceiptImageSelection
import dev.snapbudget.data.RoomExpenseRepository
import dev.snapbudget.domain.ExpenseRepository
import dev.snapbudget.domain.ImportedExpenseRepository
import dev.snapbudget.ocr.ContentResolverReceiptImageReader
import dev.snapbudget.ui.ExpenseScreen
import dev.snapbudget.ui.ExpenseViewModel
import dev.snapbudget.ui.SnapBudgetTheme

/** Opens only a user-selected local document; it does not request broad or persistable access. */
internal class OpenLocalImageDocument : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

internal fun expenseViewModelFactory(
    repository: ExpenseRepository,
    receiptImageReader: dev.snapbudget.application.ReceiptImageReader,
    importedExpenseRepository: ImportedExpenseRepository,
) = viewModelFactory {
    initializer {
        val handle = createSavedStateHandle()
        ExpenseViewModel(
            repository = repository,
            savedState = handle,
            receiptImageReader = receiptImageReader,
            importedExpenseRepository = importedExpenseRepository,
        )
    }
}

class MainActivity : ComponentActivity() {
    private var expenseViewModel: ExpenseViewModel? = null
    private val imagePicker = registerForActivityResult(OpenLocalImageDocument()) { uri ->
        val viewModel = expenseViewModel ?: return@registerForActivityResult
        if (uri == null) {
            viewModel.onImageSelectionCancelled()
        } else {
            viewModel.onTransactionImageSelected(ReceiptImageSelection(uri.toString()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = RoomExpenseRepository(ExpenseDatabase.get(applicationContext).expenses())
        val factory = expenseViewModelFactory(
            repository,
            ContentResolverReceiptImageReader(applicationContext),
            repository,
        )
        setContent {
            SnapBudgetTheme {
                val vm: ExpenseViewModel = viewModel(factory = factory)
                expenseViewModel = vm
                ExpenseScreen(vm, onChooseTransactionImage = {
                    vm.openImageImport()
                    try {
                        imagePicker.launch(Unit)
                    } catch (_: ActivityNotFoundException) {
                        vm.onImageSelectionCancelled()
                        Toast.makeText(this, "Image picker is unavailable.", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
    }
}
