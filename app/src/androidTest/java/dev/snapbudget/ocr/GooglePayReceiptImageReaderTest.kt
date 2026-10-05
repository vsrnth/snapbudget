package dev.snapbudget.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.snapbudget.ReceiptTextLayout
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class GooglePayReceiptImageReaderTest {
    @Test
    fun generatedGooglePayReceiptPassesBundledOcrAndFactoryReader() = runBlocking {
        val bytes = syntheticPng(GOOGLE_PAY_LINES)
        val fixture = Fixture(bytes)

        val result = fixture.reader().read(Fixture.SELECTION)

        assertTrue("Expected Ready, got ${result::class.simpleName}", result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertEquals(12_345L, preview.amountPaise)
        assertEquals("Synthetic Shop", preview.merchant)
        assertEquals("2025-05-03T08:22", preview.dateTime.toString())
        assertEquals("GPAY:UPI:123456789012", preview.transactionId)
        assertEquals(sha256(bytes), preview.imageHash)
        assertTrue(fixture.closed)
    }

    @Test
    fun syntheticFailedIncomingAndUnknownLayoutsAreRejected() = runBlocking {
        val fixture = Fixture(syntheticPng(GOOGLE_PAY_LINES))
        val layouts = listOf(
            googlePayLayout().copy(text = googlePayText.replace("Completed", "Payment Failed")),
            googlePayLayout().copy(text = googlePayText.replace("To: Synthetic Shop", "Received from: Synthetic Shop")),
            ReceiptTextLayout("Transaction Successful\nPaid to\nSynthetic Shop\nINR 123.45", emptyList()),
        )

        layouts.forEachIndexed { index, layout ->
            val result = fixture.reader { _, done -> try { layout } finally { done() } }
                .read(ReceiptImageSelection("content://fixture/gpay-rejected-$index"))
            assertEquals("Layout $index should be unsupported", ReceiptImageReadResult.Unsupported, result)
        }
    }

    @Test
    fun sameProviderDetailRecoversOnlyAmountAndRetainsInitialMetadata() = runBlocking {
        val bytes = syntheticPng(GOOGLE_PAY_LINES, width = 4_200, height = 3_200)
        val fixture = Fixture(bytes)
        val initial = googlePayLayout().copy(text = googlePayText.replace("INR 123.45", ""))
        val detail = googlePayLayout().copy(text = googlePayText
            .replace("INR 123.45", "INR 246.80")
            .replace("Synthetic Shop", "Changed Shop")
            .replace("3 May 2025, 8:22am", "4 May 2025, 9:30am"))
        var calls = 0

        val result = fixture.reader { _, done -> try { listOf(initial, detail)[calls++] } finally { done() } }
            .read(Fixture.SELECTION)

        assertTrue(result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertEquals(24_680L, preview.amountPaise)
        assertEquals("Synthetic Shop", preview.merchant)
        assertEquals("2025-05-03T08:22", preview.dateTime.toString())
        assertEquals("GPAY:UPI:123456789012", preview.transactionId)
        assertEquals(2, calls)
        assertTrue(fixture.closed)
    }

    @Test
    fun detailCanFillMissingDateAndIdentityWithoutReplacingKnownFields() = runBlocking {
        val fixture = Fixture(syntheticPng(GOOGLE_PAY_LINES, width = 4_200, height = 3_200))
        val bitmaps = mutableListOf<Bitmap>()
        val initial = ReceiptTextLayout(
            "Google Pay\nPayment of INR 123.45 completed\nCompleted\nTo: Initial Shop",
            emptyList(),
        )
        val detailed = ReceiptTextLayout(
            "Google Pay\nPayment of INR 999.99 completed\nCompleted\n4 May 2025, 9:30am\n" +
                "UPI transaction ID\n123456789012\nTo: Changed Shop",
            emptyList(),
        )
        var calls = 0

        val result = fixture.reader { bitmap, done ->
            bitmaps += bitmap
            try { listOf(initial, detailed)[calls++] } finally { done() }
        }.read(Fixture.SELECTION)

        assertTrue(result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertEquals(12_345L, preview.amountPaise)
        assertEquals("Initial Shop", preview.merchant)
        assertEquals("2025-05-04T09:30", preview.dateTime.toString())
        assertEquals("GPAY:UPI:123456789012", preview.transactionId)
        assertEquals(2, calls)
        assertTrue(bitmaps.all(Bitmap::isRecycled))
        assertTrue(bitmaps[1].width > bitmaps[0].width || bitmaps[1].height > bitmaps[0].height)
        assertTrue(fixture.closed)
    }

    @Test
    fun detailStillMergesRecoveredDateWhenTransactionIdentityRemainsMissing() = runBlocking {
        val fixture = Fixture(syntheticPng(GOOGLE_PAY_LINES, width = 4_200, height = 3_200))
        val initial = ReceiptTextLayout(
            "Google Pay\nPayment of INR 123.45 completed\nCompleted\nTo: Initial Shop",
            emptyList(),
        )
        val detailed = ReceiptTextLayout(
            "Google Pay\nPayment of INR 123.45 completed\nCompleted\n4 May 2025, 9:30am\nTo: Changed Shop",
            emptyList(),
        )
        var calls = 0

        val result = fixture.reader { _, done -> try { listOf(initial, detailed)[calls++] } finally { done() } }
            .read(Fixture.SELECTION)

        assertTrue(result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertEquals(12_345L, preview.amountPaise)
        assertEquals("Initial Shop", preview.merchant)
        assertEquals("2025-05-04T09:30", preview.dateTime.toString())
        assertNull(preview.transactionId)
        assertEquals(2, calls)
        assertTrue(fixture.closed)
    }

    private class Fixture(private val bytes: ByteArray) {
        var closed = false

        fun reader(recognize: (suspend (Bitmap, () -> Unit) -> ReceiptTextLayout)? = null) =
            ContentResolverReceiptImageReader(
                mimeType = { "image/png" },
                openStream = {
                    closed = false
                    object : ByteArrayInputStream(bytes) {
                        override fun close() {
                            closed = true
                            super.close()
                        }
                    }
                },
                recognizeForTest = recognize,
            )

        companion object {
            val SELECTION = ReceiptImageSelection("content://fixture/gpay")
        }
    }

    private fun googlePayLayout() = ReceiptTextLayout(googlePayText, emptyList())

    private fun syntheticPng(lines: List<String>, width: Int = 1600, height: Int = 1600): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            lines.forEachIndexed { index, line -> canvas.drawText(line, 45f, 100f + index * 105f, paint) }
            return java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val GOOGLE_PAY_LINES = listOf(
            "Google Pay",
            "To: Synthetic Shop",
            "INR 123.45",
            "Payment of INR 123.45 completed",
            "Completed",
            "3 May 2025, 8:22am",
            "UPI transaction ID",
            "123456789012",
            "To: Synthetic Shop",
            "From: Synthetic Sender",
            "Google transaction ID",
            "GPA1234567890",
        )
        val googlePayText = GOOGLE_PAY_LINES.joinToString("\n")
    }
}
