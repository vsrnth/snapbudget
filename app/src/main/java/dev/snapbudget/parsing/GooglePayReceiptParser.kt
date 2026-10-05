package dev.snapbudget.parsing

import dev.snapbudget.Receipt
import dev.snapbudget.ReceiptTextLayout
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

object GooglePayReceiptParser : ProviderReceiptParser {
    override val provider = ReceiptProvider.GOOGLE_PAY
    private val statusProblem = Regex("\\b(failed|pending|processing|refunded|reversed)\\b", RegexOption.IGNORE_CASE)
    private val dateRegex = Regex("\\b(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})\\s*,?\\s+(\\d{1,2}:\\d{2})\\s*([ap]m)\\b", RegexOption.IGNORE_CASE)
    private val dateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu h:mm a")
        .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    private val moneyRegex = Regex("(?:₹|(?<![A-Za-z])(?:Rs\\.?|INR)(?![A-Za-z]))\\s*([\\d,]+(?:\\.\\d{1,2})?)(?![\\d.])", RegexOption.IGNORE_CASE)
    private val moneyToken = Regex("""(?:₹|(?<![A-Za-z])(?:Rs\.?|INR)(?![A-Za-z]))\s*([\d,]+(?:\.\d+)?)(?![\d.\p{L}_])""", RegexOption.IGNORE_CASE)
    private val currencyMarker = Regex("₹|(?<![A-Za-z])(?:Rs\\.?|INR)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val upiLabel = Regex("\\bUPI\\s+transaction\\s+ID\\b", RegexOption.IGNORE_CASE)
    private val googleLabel = Regex("^\\s*Google\\s+transaction\\s+ID\\b", RegexOption.IGNORE_CASE)

    override fun needsMoreDetail(receipt: Receipt): Boolean = receipt.amountPaise == null ||
        receipt.merchant.isBlank() || receipt.dateTime == null || receipt.transactionId == null

    override fun mergeDetailed(initial: Receipt, detailed: Receipt): Receipt = initial.copy(
        amountPaise = initial.amountPaise ?: detailed.amountPaise,
        merchant = initial.merchant.takeIf(String::isNotBlank) ?: detailed.merchant,
        dateTime = initial.dateTime ?: detailed.dateTime,
        transactionId = initial.transactionId ?: detailed.transactionId,
    )

    override fun parse(layout: ReceiptTextLayout): Receipt {
        val text = layout.text
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val completed = lines.any { it.equals("Completed", ignoreCase = true) } && !statusProblem.containsMatchIn(text)
        val outgoing = lines.any { Regex("^To(?:\\s*:|\\s+\\S)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val incoming = Regex("\\b(?:Received\\s+(?:from|by)|Payment\\s+received)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val eligible = completed && outgoing && !incoming
        val amountMatches = moneyToken.findAll(text).toList()
        val amountValues = amountMatches.map { ReceiptAmountValidator.parseAmount(it.groupValues[1]) }
        val malformedCurrency = currencyMarker.findAll(text).count() != amountMatches.size || amountValues.any { it == null }
        val amount = if (!eligible || malformedCurrency) null else {
            amountValues.filterNotNull().distinct().singleOrNull()
                ?: if (amountMatches.isEmpty() && currencyMarker.find(text) == null) parseLayoutAmount(layout) else null
        }
        val date = dateRegex.find(text)?.let { m -> runCatching {
            LocalDateTime.parse("${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}".uppercase(Locale.ENGLISH), dateFormatter)
        }.getOrNull() }
        val merchant = detailedMerchant(lines)
        val upiResult = extractLabeledId(lines, upiLabel, isUpi = true)
        val upiId = upiResult.value
        val googleResult = extractLabeledId(lines, googleLabel, isUpi = false)
        val transactionId = when {
            upiResult.ambiguous -> null
            upiId != null -> "GPAY:UPI:$upiId"
            googleResult.ambiguous -> null
            googleResult.value != null -> "GPAY:GOOGLE:${googleResult.value}"
            else -> null
        }
        return Receipt(amount, merchant, date, transactionId, eligible)
    }

    private fun detailedMerchant(lines: List<String>): String {
        for (index in lines.indices) {
            if (Regex("^To\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(lines[index])) {
                val value = lines[index].substringAfter(":").trim()
                val continuation = lines.drop(index + 1).takeWhile(::isMerchantCandidate)
                return (listOf(value).filter(String::isNotBlank) + continuation).joinToString(" ").trim()
            }
        }
        val top = lines.firstOrNull { Regex("^To\\s+", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty()
        return top.replace(Regex("^To\\s+", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun isMerchantCandidate(line: String) = line.isNotBlank() &&
        !Regex("^(?:To|From|UPI|Google|.*\\bBank(?:ing)?\\b|Completed|Payment|UPI Intent|on Google Pay)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
        !line.contains("@") && !line.any(Char::isDigit) && !moneyRegex.containsMatchIn(line) && !upiLabel.containsMatchIn(line)

    private fun parseLayoutAmount(layout: ReceiptTextLayout): Long? {
        val visible = layout.blocks.filter { block ->
            block.text.isNotBlank() && block.left.isFinite() && block.top.isFinite() && block.right.isFinite() && block.bottom.isFinite() &&
                block.left in 0f..1f && block.top in 0f..1f && block.right in 0f..1f && block.bottom in 0f..1f &&
                block.left < block.right && block.top < block.bottom
        }.distinct()
        val payees = visible.filter { Regex("^To(?:\\s*:|\\s+\\S)", RegexOption.IGNORE_CASE).containsMatchIn(it.text.trim()) }
        if (payees.isEmpty()) return null
        val completion = visible.filter { it.text.equals("Completed", true) }.minByOrNull { it.top } ?: return null
        if (payees.none { it.top < completion.top }) return null
        val unsafeContexts = visible.filter {
            Regex("\\b(?:account|reference|transaction|phone|mobile|date|time|bank|utr|card)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(it.text)
        }
        val candidates = visible.mapNotNull { block ->
            if (block.parentText?.let { Regex("\\b(?:account|reference|transaction|phone|mobile|date|time|bank|utr|card)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true) return@mapNotNull null
            val amount = block.text.trim().let(ReceiptAmountValidator::parseAmount) ?: return@mapNotNull null
            val nearPayee = payees.any { payee ->
                block.top >= payee.bottom - 0.015f && block.bottom <= completion.top + 0.015f &&
                    verticalGap(payee, block) <= 0.08f &&
                    unsafeContexts.none { verticalGap(it, block) <= 0.08f }
            }
            amount.takeIf { nearPayee }
        }
        return candidates.distinct().singleOrNull()
    }

    private fun verticalGap(a: dev.snapbudget.ReceiptTextBlock, b: dev.snapbudget.ReceiptTextBlock) = when {
        a.bottom < b.top -> b.top - a.bottom
        b.bottom < a.top -> a.top - b.bottom
        else -> 0f
    }

    private data class IdResult(val value: String?, val ambiguous: Boolean)

    private fun extractLabeledId(lines: List<String>, label: Regex, isUpi: Boolean): IdResult {
        val found = mutableListOf<String>()
        var labelCount = 0
        var invalidLabel = false
        lines.forEachIndexed { index, line ->
            val match = label.find(line) ?: return@forEachIndexed
            labelCount++
            val inline = line.substring(match.range.last + 1).trim().trimStart(':', '-', ' ')
            val value = inline.ifBlank { lines.getOrNull(index + 1).orEmpty() }.trim()
            if (validId(value, isUpi)) found += value else invalidLabel = true
        }
        val unique = found.distinct()
        return IdResult(unique.singleOrNull(), invalidLabel || (labelCount > 0 && unique.size != 1) || unique.size > 1)
    }

    private fun validId(value: String, isUpi: Boolean) = if (isUpi) value.matches(Regex("[0-9]{12}"))
        else value.length in 6..128 && value.matches(Regex("[A-Za-z0-9_-]+"))

    internal fun hasGoogleTransactionIdLabel(text: String) =
        text.lines().any { googleLabel.containsMatchIn(it) }
}
