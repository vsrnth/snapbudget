package dev.snapbudget.application

import java.time.LocalDateTime

/** Opaque platform selection identifier; implementations decide eligibility without exposing it here. */
@JvmInline
value class ReceiptImageSelection(val value: String)

/** Safe-to-display recognized suggestions and stable image identity; raw OCR output is never included. */
data class ReceiptImagePreview(
    val amountPaise: Long?,
    val merchant: String?,
    val dateTime: LocalDateTime?,
    val imageHash: String,
    val transactionId: String?,
)

sealed interface ReceiptImageReadResult {
    data class Ready(val preview: ReceiptImagePreview) : ReceiptImageReadResult
    data object InvalidSelection : ReceiptImageReadResult
    data object Unreadable : ReceiptImageReadResult
    data object Unsupported : ReceiptImageReadResult
    data object Failed : ReceiptImageReadResult
}

/** Platform adapter boundary; implementations return typed failures and must not expose raw OCR text. */
interface ReceiptImageReader {
    suspend fun read(selection: ReceiptImageSelection): ReceiptImageReadResult
}
