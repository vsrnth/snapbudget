package dev.snapbudget.parsing

import dev.snapbudget.Receipt
import dev.snapbudget.ReceiptTextLayout

enum class ReceiptProvider { PHONE_PE, GOOGLE_PAY }

interface ProviderReceiptParser {
    val provider: ReceiptProvider
    fun parse(layout: ReceiptTextLayout): Receipt

    fun needsMoreDetail(receipt: Receipt): Boolean = receipt.amountPaise == null

    fun mergeDetailed(initial: Receipt, detailed: Receipt): Receipt =
        initial.copy(amountPaise = initial.amountPaise ?: detailed.amountPaise)
}

object ReceiptParserFactory {
    fun create(layout: ReceiptTextLayout): ProviderReceiptParser? {
        val text = layout.text
        val phonePeOwner = PhonePeReceiptParser.hasPhonePeTransactionIdLabel(text)
        val googlePayOwner = GooglePayReceiptParser.hasGoogleTransactionIdLabel(text)
        if (phonePeOwner && googlePayOwner) return null
        if (phonePeOwner) return PhonePeReceiptParser
        if (googlePayOwner) return GooglePayReceiptParser

        val phonePe = Regex("""\bPhone\s*Pe\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val googlePay = Regex("""\b(?:Google\s*Pay|G\s*Pay|GPay)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        if (phonePe && googlePay) return null
        if (phonePe) return PhonePeReceiptParser
        if (googlePay) return GooglePayReceiptParser
        return null
    }

    fun create(text: String): ProviderReceiptParser? = create(ReceiptTextLayout(text, emptyList()))
}
