package dev.snapbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReceiptParserTest {
    private val text by lazy {
        javaClass.getResourceAsStream("/synthetic_receipt.txt")!!.bufferedReader().use { it.readText() }
    }

    @Test fun parsesAmountMerchantDirectionStatusAndTransactionId() {
        val receipt = ReceiptParser.parse(text)
        assertEquals(12_550L, receipt.amountPaise)
        assertEquals("Synthetic Cafe", receipt.merchant)
        assertTrue(receipt.eligible)
        assertEquals("T123456789012345678", receipt.transactionId)
    }

    @Test fun parsesReceiptDate() {
        // This is intentionally a desired behavior assertion: current parser uppercases MMM,
        // then uses a case-sensitive formatter, so this test is expected to expose the bug.
        assertEquals(LocalDateTime.of(2025, 5, 3, 21, 41), ReceiptParser.parse(text).dateTime)
    }

    @Test fun statusAndDirectionRejectUnsuccessfulAndIncomingPayments() {
        assertFalse(ReceiptParser.parse("Transaction Failed\nPaid to\nMerchant\n₹1").eligible)
        assertFalse(ReceiptParser.parse("Transaction Successful\nReceived from\nMerchant\n₹1").eligible)
        assertFalse(ReceiptParser.parse("Transaction Successful\nPaid to\nMerchant\nTransaction Refunded\n₹1").eligible)
    }

    @Test fun parsesValidAmountsWithExactPaiseAndIndianGrouping() {
        assertEquals(1L, ReceiptParser.parseAmount("0.01"))
        assertEquals(120L, ReceiptParser.parseAmount("1.2"))
        assertEquals(123_456L, ReceiptParser.parseAmount("1,234.56"))
        assertEquals(123_456_789L, ReceiptParser.parseAmount("12,34,567.89"))
    }

    @Test fun rejectsZeroNegativeOverflowAndMalformedAmounts() {
        assertNull(ReceiptParser.parseAmount("0"))
        assertNull(ReceiptParser.parseAmount("-1.00"))
        assertNull(ReceiptParser.parseAmount("92233720368547758.08"))
        assertNull(ReceiptParser.parseAmount("12,34.56"))
        assertNull(ReceiptParser.parseAmount("1.234"))
    }
}
