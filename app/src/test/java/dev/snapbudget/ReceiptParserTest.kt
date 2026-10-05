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
        assertEquals(LocalDateTime.of(2025, 5, 3, 21, 41), ReceiptParser.parse(text).dateTime)
    }

    @Test fun parsesMixedCaseMonthsAndAmPmIncludingNoonAndMidnight() {
        assertEquals(LocalDateTime.of(2025, 5, 3, 21, 41), parseDate("9:41 pM on 3 mAy 2025"))
        assertEquals(LocalDateTime.of(2025, 5, 3, 0, 0), parseDate("12:00 aM on 3 MaY 2025"))
        assertEquals(LocalDateTime.of(2025, 5, 3, 12, 0), parseDate("12:00 Pm on 3 may 2025"))
    }

    @Test fun rejectsInvalidCalendarDates() {
        assertNull(parseDate("9:41 PM on 31 Feb 2025"))
        assertNull(parseDate("9:41 PM on 29 Feb 2025"))
        assertEquals(LocalDateTime.of(2024, 2, 29, 21, 41), parseDate("9:41 PM on 29 Feb 2024"))
    }

    @Test fun statusAndDirectionRejectUnsuccessfulAndIncomingPayments() {
        assertFalse(ReceiptParser.parse("Transaction Failed\nPaid to\nMerchant\n₹1").eligible)
        assertFalse(ReceiptParser.parse("Transaction Successful\nReceived from\nMerchant\n₹1").eligible)
        assertFalse(ReceiptParser.parse("Transaction Successful\nReceived by\nMerchant\n₹1").eligible)
        assertFalse(ReceiptParser.parse("Transaction Successful\nPaid to\nMerchant\nTransaction Refunded\n₹1").eligible)
    }

    @Test fun parsesValidAmountsWithExactPaiseAndIndianGrouping() {
        assertEquals(1L, ReceiptParser.parseAmount("0.01"))
        assertEquals(120L, ReceiptParser.parseAmount("1.2"))
        assertEquals(123_456L, ReceiptParser.parseAmount("1,234.56"))
        assertEquals(123_456_789L, ReceiptParser.parseAmount("12,34,567.89"))
    }

    @Test fun parsesSyntheticStructuredAmountWhenCurrencyMarkerIsMissingOrSplit() {
        val unlabeled = javaClass.getResourceAsStream("/synthetic_receipt_missing_currency.txt")!!
            .bufferedReader().use { it.readText() }
        assertEquals(34_567L, ReceiptParser.parse(unlabeled).amountPaise)

        val splitMarker = "PhonePe\nTransaction Successful\nPaid to\nSynthetic Bakery\n₹\n234.56"
        assertEquals(23_456L, ReceiptParser.parse(splitMarker).amountPaise)
    }

    @Test fun rejectsAmbiguousAndUnstructuredNumbers() {
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\n125.50\n126.50").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\nT123456789012345678\n03 MAY 2025").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\n125.50").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\nAccount number\n9876543210").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\nReference number\n123456789012345678").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\nStatus: Pending\n125.50").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nReceived from\nSynthetic Cafe\n125.50").amountPaise)
    }

    @Test fun explicitAmountLabelMustHaveOneValidCandidate() {
        assertEquals(45_670L, ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\nAmount: 456.70").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\nAmount: 456.789").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\nAmount: 456.70\nAmount: 457.70").amountPaise)
    }

    @Test fun currencyMarkersRemainSupportedAndCompetingCurrencyAmountsAreAmbiguous() {
        assertEquals(12_550L, ReceiptParser.parse("₹125.50").amountPaise)
        assertEquals(12_550L, ReceiptParser.parse("Rs. 125.50").amountPaise)
        assertEquals(12_550L, ReceiptParser.parse("Rs125.50").amountPaise)
        assertEquals(12_550L, ReceiptParser.parse("INR125.50").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Bakers\n245.68").amountPaise)
        assertNull(ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Transfers\n245.68").amountPaise)
        assertNull(ReceiptParser.parse("₹125.50\nINR 126.50").amountPaise)
    }

    private fun parseDate(value: String) = ReceiptParser.parse("Transaction Successful\nPaid to\nSynthetic Cafe\n$value").dateTime

    @Test fun rejectsZeroNegativeOverflowAndMalformedAmounts() {
        assertNull(ReceiptParser.parseAmount("0"))
        assertNull(ReceiptParser.parseAmount("-1.00"))
        assertNull(ReceiptParser.parseAmount("92233720368547758.08"))
        assertNull(ReceiptParser.parseAmount("12,34.56"))
        assertNull(ReceiptParser.parseAmount("1.234"))
    }
}
