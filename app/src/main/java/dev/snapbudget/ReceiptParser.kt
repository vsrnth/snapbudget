package dev.snapbudget

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

data class Receipt(
    val amountPaise: Long?,
    val merchant: String,
    val dateTime: LocalDateTime?,
    val transactionId: String?,
    val eligible: Boolean,
)

object ReceiptParser {
    private val money = Regex("""(?:₹|(?<![A-Za-z])(?:Rs\.?|INR)(?![A-Za-z]))\s*([\d,]+(?:\.\d{1,2})?)(?![\d.])""", RegexOption.IGNORE_CASE)
    private val numericAmount = Regex("""(?:\d+|\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})(?:\.\d{1,2})?""")
    private val amountLabel = Regex("""^\s*(?:amount(?:\s+paid)?|total)\s*:?\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)
    private val currencyMarker = Regex("""(?:₹|(?<![A-Za-z])(?:Rs\.?|INR)(?![A-Za-z]))""", RegexOption.IGNORE_CASE)
    private val paymentContext = Regex("""\b(?:paid\s+(?:to|by)|debited\s+from)\b""", RegexOption.IGNORE_CASE)
    private val unsafeContext = Regex("""\b(?:transaction|reference|ref\.?|utr|account|acct|phone|mobile|contact|card|masked|payment\s+id|date|time|bank(?:ing)?\s+name)\b""", RegexOption.IGNORE_CASE)
    private val sectionBoundary = Regex("""\b(?:transaction\s+details|payment\s+details|bank\s+details|account\s+details|transaction\s+id|payment\s+id|reference|utr|account|acct|phone|mobile|contact|card|date|time)\b""", RegexOption.IGNORE_CASE)
    private val date = Regex("""(\d{1,2}:\d{2}\s*[ap]m)\s+on\s+(\d{1,2}\s+[A-Za-z]{3}\s+\d{4})""", RegexOption.IGNORE_CASE)
    private val id = Regex("""\bT\d{15,35}\b""", RegexOption.IGNORE_CASE)
    private val invalidStatus = Regex("""\b(failed|pending|processing|refunded)\b""", RegexOption.IGNORE_CASE)
    private val dateFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("h:mm a d MMM uuuu")
        .toFormatter(Locale.ENGLISH)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(layout: ReceiptTextLayout): Receipt {
        val parsed = parse(layout.text)
        if (parsed.amountPaise != null || !parsed.eligible || hasExplicitAmountSignal(layout.text)) return parsed
        val layoutAmount = parseLayoutAmount(layout.blocks)
        return parsed.copy(amountPaise = layoutAmount)
    }

    fun parse(text: String): Receipt {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val successful = text.contains("Transaction Successful", true) && !invalidStatus.containsMatchIn(text)
        val outgoing = text.contains("Paid to", true) && !text.contains("Received from", true) &&
            !text.contains("Received by", true)
        val eligible = successful && outgoing
        val amount = parseReceiptAmount(lines, text, eligible)
        val match = date.find(text)
        val time = match?.let {
            runCatching {
                val value = (it.groupValues[1] + " " + it.groupValues[2])
                    .replace(Regex("""\s+"""), " ").uppercase(Locale.ENGLISH)
                LocalDateTime.parse(value, dateFormatter)
            }.getOrNull()
        }
        val banking = lines.indexOfFirst { it.contains("Banking Name", true) }
        val bankName = if (banking >= 0) {
            val suffix = lines[banking].substringAfter(":", "").trim()
            suffix.ifBlank {
                lines.drop(banking + 1).firstOrNull { it != ":" }.orEmpty().removePrefix(":").trim()
            }
        } else ""
        val paid = lines.indexOfFirst { it.contains("Paid to", true) }
        val merchant = bankName.ifBlank {
            if (paid >= 0) lines.drop(paid + 1).firstOrNull {
                !money.containsMatchIn(it) && !it.contains("@") && it.length > 2
            }.orEmpty() else ""
        }.replace(Regex("""[✓✔]"""), "").trim()
        return Receipt(amount, merchant, time, id.find(text)?.value?.uppercase(Locale.ROOT), eligible)
    }

    private fun hasExplicitAmountSignal(text: String): Boolean =
        currencyMarker.containsMatchIn(text) || text.lines().any { amountLabel.matches(it.trim()) }

    private fun parseLayoutAmount(blocks: List<ReceiptTextBlock>): Long? {
        val visible = blocks.filter { block ->
            block.text.isNotBlank() && block.left.isFinite() && block.top.isFinite() &&
                block.right.isFinite() && block.bottom.isFinite() && block.left in 0f..1f &&
                block.top in 0f..1f && block.right in 0f..1f && block.bottom in 0f..1f &&
                block.left < block.right && block.top < block.bottom
        }.distinct()
        val markers = visible.filter { paymentContext.containsMatchIn(it.text) }
        if (markers.isEmpty()) return null
        val payees = visible.filter { block ->
            block.text.length > 2 && !numericAmount.matches(block.text.trim()) &&
                !paymentContext.containsMatchIn(block.text) && !unsafeContext.containsMatchIn(block.text) &&
                !block.text.contains("PhonePe", ignoreCase = true) &&
                !block.text.contains("Transaction Successful", ignoreCase = true) &&
                !currencyMarker.containsMatchIn(block.text) && !amountLabel.matches(block.text.trim())
        }
        val candidates = visible.mapNotNull { block ->
            val amount = block.text.trim().takeIf(numericAmount::matches)?.let(::parseAmount) ?: return@mapNotNull null
            if (block.parentText?.let(unsafeContext::containsMatchIn) == true) return@mapNotNull null
            val qualified = markers.any { marker ->
                val nearbyPayees = payees.filter { payee ->
                    payee.top >= marker.top - GEOMETRY_TOLERANCE &&
                        verticalGap(marker, payee) <= PAYMENT_REGION_GAP
                }
                nearbyPayees.any { payee ->
                    verticalGap(payee, block) <= PAYMENT_REGION_GAP &&
                        block.centerY() >= marker.top - GEOMETRY_TOLERANCE &&
                        !visible.any { context ->
                            context !== block && unsafeContext.containsMatchIn(context.text) &&
                                sameVisualRow(context, block)
                        } &&
                        !visible.any { boundary ->
                            sectionBoundary.containsMatchIn(boundary.text) &&
                                boundary.top > payee.bottom && boundary.top < block.top
                        }
                }
            }
            amount.takeIf { qualified }
        }
        return candidates.distinct().singleOrNull()
    }

    private fun ReceiptTextBlock.centerY() = (top + bottom) / 2f

    private fun verticalGap(first: ReceiptTextBlock, second: ReceiptTextBlock): Float = when {
        first.bottom < second.top -> second.top - first.bottom
        second.bottom < first.top -> first.top - second.bottom
        else -> 0f
    }

    private fun sameVisualRow(first: ReceiptTextBlock, second: ReceiptTextBlock): Boolean =
        verticalGap(first, second) <= ROW_GAP

    private fun parseReceiptAmount(lines: List<String>, text: String, eligible: Boolean): Long? {
        data class Candidate(val offset: Int, val amountPaise: Long)
        val candidates = mutableListOf<Candidate>()
        money.findAll(text).forEach { match ->
            parseAmount(match.groupValues[1])?.let { candidates += Candidate(match.range.first, it) }
        }
        if (eligible) {
            val paidIndex = lines.indexOfFirst { it.contains("Paid to", true) }
            if (paidIndex >= 0) {
                val boundary = (paidIndex + 1 until lines.size).firstOrNull { index ->
                    date.containsMatchIn(lines[index]) || id.containsMatchIn(lines[index])
                } ?: lines.size
                val lineOffsets = lines.indices.map { index -> lines.take(index).sumOf { it.length + 1 } }
                var invalidExplicitLabel = false
                for (index in paidIndex + 1 until boundary) {
                    val label = amountLabel.matchEntire(lines[index]) ?: continue
                    val inlineValue = label.groupValues[1]
                    val amountLineIndex = if (inlineValue.isBlank()) index + 1 else index
                    val value = if (inlineValue.isBlank()) lines.getOrNull(amountLineIndex).orEmpty() else inlineValue
                    val parsed = value.takeIf(numericAmount::matches)?.let(::parseAmount)
                    if (parsed == null || amountLineIndex >= lines.size) invalidExplicitLabel = true
                    else candidates += Candidate(
                        lineOffsets[amountLineIndex] + if (inlineValue.isBlank()) 0 else lines[index].indexOf(value),
                        parsed,
                    )
                }
                if (invalidExplicitLabel) return null
            }
        }
        return candidates.distinctBy { it.offset to it.amountPaise }.singleOrNull()?.amountPaise
    }

    private const val PAYMENT_REGION_GAP = 0.08f
    private const val GEOMETRY_TOLERANCE = 0.015f
    private const val ROW_GAP = 0.025f

    fun parseAmount(value: String): Long? = runCatching {
        val clean = value.trim()
        require(numericAmount.matches(clean))
        BigDecimal(clean.replace(",", "")).movePointRight(2).longValueExact().also { require(it > 0) }
    }.getOrNull()
}
