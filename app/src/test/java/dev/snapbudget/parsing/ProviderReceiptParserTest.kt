package dev.snapbudget.parsing

import dev.snapbudget.ReceiptTextLayout
import dev.snapbudget.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ProviderReceiptParserTest {
    private val sample by lazy {
        javaClass.getResourceAsStream("/gpay_synthetic_receipt.txt")!!.bufferedReader().use { it.readText() }
    }

    @Test fun factoryRecognizesProvidersAndRejectsUnknownOrMixedMarkers() {
        assertEquals(ReceiptProvider.GOOGLE_PAY, ReceiptParserFactory.create(sample)?.provider)
        assertEquals(ReceiptProvider.PHONE_PE, ReceiptParserFactory.create("PhonePe\nTransaction Successful")?.provider)
        assertNull(ReceiptParserFactory.create("A completed payment to a merchant"))
        assertNull(ReceiptParserFactory.create("PhonePe\nG Pay\nCompleted"))
        assertEquals(ReceiptProvider.GOOGLE_PAY, ReceiptParserFactory.create(
            "UPI transaction ID\n123456789012\nGoogle transaction ID\nopaqueABC",
        )?.provider)
    }

    @Test fun ownershipIdsOutrankRecipientBrandMentionsAndConflictingOwnerIdsStayAmbiguous() {
        val phonePeReceipt = """PhonePe
Transaction Successful
Paid to
Synthetic Shop
₹123.45
3 May 2025, 8:22am
Sent to G Pay
PhonePe Transaction ID
T123456789012345678
G Pay"""
        assertEquals(ReceiptProvider.PHONE_PE, ReceiptParserFactory.create(phonePeReceipt)?.provider)
        assertTrue(ReceiptParserFactory.create(phonePeReceipt)!!.parse(ReceiptTextLayout(phonePeReceipt, emptyList())).eligible)

        val googleReceipt = sample.replace("From: Synthetic Sender (Bank)", "From: Synthetic PhonePe Sender (Bank)")
        assertEquals(ReceiptProvider.GOOGLE_PAY, ReceiptParserFactory.create(googleReceipt)?.provider)

        val conflictingOwners = "$phonePeReceipt\nGoogle transaction ID\nOpaqueSynthetic123"
        assertNull(ReceiptParserFactory.create(conflictingOwners))

        val phonePeMalformedId = phonePeReceipt.replace("T123456789012345678", "not-a-valid-id")
        assertEquals(ReceiptProvider.PHONE_PE, ReceiptParserFactory.create(phonePeMalformedId)?.provider)
        assertNull(ReceiptParserFactory.create(phonePeMalformedId)!!.parse(ReceiptTextLayout(phonePeMalformedId, emptyList())).transactionId)

        val googleMalformedId = sample
            .replace("UPI transaction ID\n123456789012\n", "")
            .replace("Google transaction ID\nAbC123xyz_456", "Google transaction ID\nbad Google ID!")
            .replace("From: Synthetic Sender (Bank)", "From: Synthetic PhonePe Sender (Bank)")
        assertEquals(ReceiptProvider.GOOGLE_PAY, ReceiptParserFactory.create(googleMalformedId)?.provider)
        assertNull(ReceiptParserFactory.create(googleMalformedId)!!.parse(ReceiptTextLayout(googleMalformedId, emptyList())).transactionId)

        assertNull(ReceiptParserFactory.create(
            "PhonePe Transaction ID\nnot-valid\nGoogle transaction ID\nalso-invalid",
        ))
    }

    @Test fun parsesCompletedOutgoingPaymentAndPrefersDetailedMerchant() {
        val receipt = ReceiptParserFactory.create(sample)!!.parse(ReceiptTextLayout(sample, emptyList()))
        assertTrue(receipt.eligible)
        assertEquals(12_345L, receipt.amountPaise)
        assertEquals("Synthetic Healthcare Private Limited", receipt.merchant)
        assertEquals(LocalDateTime.of(2025, 5, 3, 8, 22), receipt.dateTime)
        assertEquals("GPAY:UPI:123456789012", receipt.transactionId)
    }

    @Test fun repeatedMatchingAmountsAreSafeButConflictingOrMalformedValuesAreRejected() {
        assertEquals(12_345L, parse(sample).amountPaise)
        assertNull(parse(sample.replace("Payment of ₹123.45", "Payment of ₹124.45")).amountPaise)
        assertNull(parse(sample.replace("Payment of ₹123.45", "Payment of ₹123.456")).amountPaise)
        assertNull(parse(sample.replace("Payment of ₹123.45", "Payment of ₹92233720368547758.08")).amountPaise)
        for (malformed in listOf("₹123.45.67", "₹123.45x", "₹12,34.56", "₹-123.45", "₹0.00")) {
            assertNull(parse(sample.replace("Payment of ₹123.45", "Payment of $malformed")).amountPaise)
        }
    }

    @Test fun failedPendingProcessingRefundedReversedAndIncomingPaymentsAreIneligible() {
        for (status in listOf("Failed", "Pending", "Processing", "Refunded", "Reversed")) {
            val receipt = parse(sample.replace("Completed", status))
            assertFalse(receipt.eligible)
            assertNull(receipt.amountPaise)
        }
        val incoming = sample.replace("To: Synthetic Healthcare Private Limited", "From: Synthetic Healthcare Private Limited")
            .replace("Payment of ₹123.45 completed", "Payment received ₹123.45")
        assertFalse(parse(incoming).eligible)
    }

    @Test fun malformedOrConflictingIdsAreNotSelectedAndGoogleFallbackIsNamespaced() {
        val upiLabel = "UPI transaction ID\n123456789012"
        val googleLabel = "Google transaction ID\nAbC123xyz_456"
        val fallback = parse(sample.replace("$upiLabel\n", "").replace("$googleLabel", googleLabel))
        assertEquals("GPAY:GOOGLE:AbC123xyz_456", fallback.transactionId)
        assertNull(parse(sample.replace("$upiLabel", "UPI transaction ID\n12345")).transactionId)
        assertNull(parse(sample.replace(upiLabel, "$upiLabel\nUPI transaction ID\n123456789013")).transactionId)
        assertNull(parse(sample.replace(upiLabel, "$upiLabel\nUPI transaction ID\nnot-an-id")).transactionId)
        assertEquals("GPAY:UPI:123456789012", parse(sample.replace(googleLabel, "Google transaction ID\nmalformed Google ID!")).transactionId)
    }

    @Test fun providerDetailPoliciesFillMissingFieldsAndPreserveKnownMetadata() {
        val now = LocalDateTime.of(2025, 5, 3, 8, 22)
        val initial = Receipt(12_345L, "Synthetic Store", now, null, eligible = true)
        val detailed = Receipt(99_999L, "Detailed Store", now.plusHours(1), "GPAY:UPI:123456789012", eligible = false)

        assertTrue(GooglePayReceiptParser.needsMoreDetail(initial))
        assertFalse(GooglePayReceiptParser.needsMoreDetail(initial.copy(transactionId = detailed.transactionId)))
        assertEquals(
            Receipt(12_345L, "Synthetic Store", now, detailed.transactionId, eligible = true),
            GooglePayReceiptParser.mergeDetailed(initial, detailed),
        )
        assertEquals(
            Receipt(99_999L, "Synthetic Store", now, null, eligible = true),
            PhonePeReceiptParser.mergeDetailed(initial.copy(amountPaise = null), detailed),
        )
        assertFalse(PhonePeReceiptParser.needsMoreDetail(initial))
        assertTrue(PhonePeReceiptParser.needsMoreDetail(initial.copy(amountPaise = null)))
    }

    @Test fun invalidCalendarDateDoesNotParseAndOnlyExplicitPaymentRegionCanSupplyMoney() {
        assertNull(parse(sample.replace("3 May 2025", "31 Feb 2025")).dateTime)
        val noCurrency = sample.replace("₹123.45", "123.45")
        assertNull(parse(noCurrency).amountPaise)
        val layoutOnly = ReceiptTextLayout(
            text = noCurrency,
            blocks = listOf(
                block("To: Synthetic Shop", 0.1f, 0.2f),
                block("123.45", 0.1f, 0.25f),
                block("Completed", 0.1f, 0.30f),
            ),
        )
        assertEquals(12_345L, ReceiptParserFactory.create(layoutOnly)!!.parse(layoutOnly).amountPaise)
        val missingCompletion = layoutOnly.copy(blocks = layoutOnly.blocks.filterNot { it.text == "Completed" })
        assertNull(ReceiptParserFactory.create(missingCompletion)!!.parse(missingCompletion).amountPaise)
        val unsafeNumber = layoutOnly.copy(blocks = listOf(
            block("To: Synthetic Shop", 0.1f, 0.2f),
            block("9876543210", 0.1f, 0.25f),
            block("Account Number", 0.1f, 0.26f),
            block("Completed", 0.1f, 0.30f),
        ))
        assertNull(ReceiptParserFactory.create(unsafeNumber)!!.parse(unsafeNumber).amountPaise)
    }

    private fun parse(text: String) = ReceiptParserFactory.create(text)!!.parse(ReceiptTextLayout(text, emptyList()))

    private fun block(text: String, left: Float, top: Float) = dev.snapbudget.ReceiptTextBlock(
        text, left, top, left + 0.3f, top + 0.02f,
    )
}
