package dev.snapbudget

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Receipt(
    val amountPaise: Long?,
    val merchant: String,
    val dateTime: LocalDateTime?,
    val transactionId: String?,
    val eligible: Boolean,
)

object ReceiptParser {
    private val money = Regex("""(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d{1,2})?)(?![\d.])""", RegexOption.IGNORE_CASE)
    private val date = Regex("""(\d{1,2}:\d{2}\s*[ap]m)\s+on\s+(\d{1,2}\s+[A-Za-z]{3}\s+\d{4})""", RegexOption.IGNORE_CASE)
    private val id = Regex("""\bT\d{15,35}\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Receipt {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val amount = money.find(text)?.groupValues?.get(1)?.let(::parseAmount)
        val match = date.find(text)
        val time = match?.let {
            runCatching {
                val value = (it.groupValues[1] + " " + it.groupValues[2])
                    .replace(Regex("""\s+"""), " ").uppercase(Locale.ENGLISH)
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern("h:mm a d MMM uuuu", Locale.ENGLISH))
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
        val successful = text.contains("Transaction Successful", true) &&
            !Regex("""\b(failed|pending|processing|refunded)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val outgoing = text.contains("Paid to", true) &&
            !text.contains("Received from", true)
        return Receipt(amount, merchant, time, id.find(text)?.value?.uppercase(Locale.ROOT), successful && outgoing)
    }

    fun parseAmount(value: String): Long? = runCatching {
        val clean = value.trim()
        require(Regex("""(?:\d+|\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})(?:\.\d{1,2})?""").matches(clean))
        BigDecimal(clean.replace(",", "")).movePointRight(2).longValueExact().also { require(it > 0) }
    }.getOrNull()
}
