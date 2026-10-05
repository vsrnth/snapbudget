package dev.snapbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptLayoutAmountTest {
    @Test fun extractsCurrencylessWholeNumberNearPayeeDespiteFlattenedTransactionDetailsOrder() {
        val layout = ReceiptTextLayout(
            text = "PhonePe\nTransaction Successful\nPaid to\nSynthetic Bakers\nTransaction Details\nReference No\n9876543210",
            blocks = paymentBlocks(amounts = listOf("245.68"), referenceNumbers = listOf("9876543210")),
        )
        assertEquals(24_568L, ReceiptParser.parse(layout).amountPaise)
    }

    @Test fun doesNotInferAmountWithoutVisualPaymentAndPayeeContext() {
        val noGeometry = ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\n123.45",
            emptyList(),
        )
        val noPayment = ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nSynthetic Grocer\n123.45",
            listOf(block("Synthetic Grocer", 0.1f, 0.2f), block("123.45", 0.1f, 0.25f)),
        )
        assertNull(ReceiptParser.parse(noGeometry).amountPaise)
        assertNull(ReceiptParser.parse(noPayment).amountPaise)
    }

    @Test fun ignoresReferenceAccountPhoneAndDateContextNumbers() {
        val blocks = paymentBlocks(amounts = emptyList(), referenceNumbers = listOf("9876543210")) + listOf(
            block("Account Number", 0.1f, 0.68f), block("12345678", 0.1f, 0.71f),
            block("Phone", 0.1f, 0.78f), block("9123456780", 0.1f, 0.81f),
            block("Date", 0.1f, 0.87f), block("03052025", 0.1f, 0.90f),
        )
        assertNull(ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\nTransaction Details\nReference 9876543210\nAccount Number 12345678\nPhone 9123456780\nDate 03052025",
            blocks,
        )).amountPaise)
        assertNull(ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\nMasked account number •••• 1234",
            paymentBlocks(emptyList()) + block("1234", 0.08f, 0.26f)
                .copy(parentText = "Masked account number •••• 1234"),
        )).amountPaise)
    }

    @Test fun rejectsMalformedNonpositiveOverflowAndConflictingContextualAmounts() {
        for (value in listOf("0", "-12.50", "12.345", "92233720368547758.08")) {
            assertNull(ReceiptParser.parse(ReceiptTextLayout(
                "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\n$value",
                paymentBlocks(listOf(value)),
            )).amountPaise)
        }
        assertNull(ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer",
            paymentBlocks(listOf("245.68", "246.68")),
        )).amountPaise)
    }

    @Test fun agreeingRepeatedAmountsAreAcceptedOnlyWhenEachHasPaymentContext() {
        val blocks = listOf(
            block("Paid to", 0.08f, 0.18f), block("Synthetic Grocer", 0.08f, 0.22f), block("245.68", 0.08f, 0.26f),
            block("Debited from", 0.08f, 0.50f), block("Synthetic Grocer", 0.08f, 0.54f), block("245.68", 0.08f, 0.58f),
            block("Transaction ID", 0.08f, 0.78f), block("9876543210", 0.08f, 0.81f),
        )
        val layout = ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\nDebited from\nSynthetic Grocer\nTransaction ID\n9876543210\n245.68",
            blocks,
        )
        assertEquals(24_568L, ReceiptParser.parse(layout).amountPaise)
    }

    @Test fun layoutThresholdsAreImageScaleInvariantAndInvalidDirectionsNeverUseFallback() {
        assertNull(ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid by\nSynthetic Grocer\n245.68",
            paymentBlocks(listOf("245.68"), direction = "Paid by"),
        )).amountPaise)
        val scaledResults = listOf(0.5f, 2f).map { scale ->
            val width = 1000f * scale
            val height = 1000f * scale
            fun scaledBlock(text: String, leftPx: Float, topPx: Float) = block(
                text, leftPx * scale / width, topPx * scale / height,
            )
            ReceiptParser.parse(ReceiptTextLayout(
                "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\nDebited from\nSynthetic wallet\n245.68",
                listOf(
                    scaledBlock("Paid to", 80f, 180f),
                    scaledBlock("Synthetic Grocer", 80f, 220f),
                    scaledBlock("Debited from", 80f, 500f),
                    scaledBlock("Synthetic wallet", 80f, 540f),
                    scaledBlock("245.68", 80f, 580f),
                ),
            )).amountPaise
        }
        assertEquals(listOf(24_568L, 24_568L), scaledResults)
        val invalidGeometry = listOf(
            block("Paid to", 0.08f, 0.18f), block("Synthetic Grocer", 0.08f, 0.22f),
            block("245.68", 0.08f, 0.26f), block("245.68", 0.08f, 0.9f).copy(bottom = 1.1f),
            block("245.68", 0.08f, 0.3f).copy(right = 1.1f),
            block("245.68", 0.08f, 0.3f).copy(bottom = 0.3f),
        )
        assertEquals(24_568L, ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\n245.68",
            invalidGeometry,
        )).amountPaise)
        assertNull(ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\n245.68",
            invalidGeometry.drop(2),
        )).amountPaise)

        for (text in listOf(
            "PhonePe\nTransaction Failed\nPaid to\nSynthetic Grocer\n245.68",
            "PhonePe\nTransaction Successful\nReceived from\nSynthetic Grocer\n245.68",
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\nTransaction Refunded\n245.68",
        )) {
            assertNull(ReceiptParser.parse(ReceiptTextLayout(text, paymentBlocks(listOf("245.68")))).amountPaise)
        }
    }

    @Test fun explicitAndCurrencyAmountsAreNotOverriddenByLayoutCandidates() {
        val blocks = paymentBlocks(listOf("245.68"))
        assertNull(ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\nAmount: 12.345\n245.68",
            blocks,
        )).amountPaise)
        assertEquals(12_345L, ReceiptParser.parse(ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Grocer\nINR 123.45",
            blocks,
        )).amountPaise)
    }

    private fun paymentBlocks(
        amounts: List<String>,
        referenceNumbers: List<String> = emptyList(),
        direction: String = "Paid to",
    ): List<ReceiptTextBlock> = buildList {
        add(block(direction, 0.08f, 0.18f))
        add(block("Synthetic Grocer", 0.08f, 0.22f))
        amounts.forEachIndexed { index, amount -> add(block(amount, 0.08f, 0.26f + index * 0.025f)) }
        add(block("Transaction Details", 0.08f, 0.58f))
        referenceNumbers.forEachIndexed { index, number ->
            add(block("Reference No", 0.08f, 0.62f + index * 0.04f))
            add(block(number, 0.08f, 0.65f + index * 0.04f))
        }
    }

    private fun block(text: String, left: Float, top: Float) = ReceiptTextBlock(
        text = text,
        left = left,
        top = top,
        right = left + 0.3f,
        bottom = top + 0.02f,
    )
}
