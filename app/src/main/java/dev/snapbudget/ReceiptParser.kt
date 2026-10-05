package dev.snapbudget

import dev.snapbudget.parsing.PhonePeReceiptParser
import dev.snapbudget.parsing.ReceiptAmountValidator

data class Receipt(
    val amountPaise: Long?,
    val merchant: String,
    val dateTime: java.time.LocalDateTime?,
    val transactionId: String?,
    val eligible: Boolean,
)

/** Backward-compatible PhonePe parser entry point. */
object ReceiptParser {
    fun parse(layout: ReceiptTextLayout): Receipt = PhonePeReceiptParser.parse(layout)
    fun parse(text: String): Receipt = PhonePeReceiptParser.parse(text)
    fun parseAmount(value: String): Long? = ReceiptAmountValidator.parseAmount(value)
}
