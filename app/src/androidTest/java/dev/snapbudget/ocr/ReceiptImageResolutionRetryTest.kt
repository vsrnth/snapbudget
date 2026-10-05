package dev.snapbudget.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
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
import java.io.FilterInputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ReceiptImageResolutionRetryTest {
    @Test
    fun recoveredAmountKeepsMerchantDateAndTransactionIdFromInitialRecognition() = runBlocking {
        val fixture = FixtureImage(3000, 1200)
        val bitmapSizes = mutableListOf<Pair<Int, Int>>()
        val reader = fixture.reader { bitmap ->
            bitmapSizes += bitmap.width to bitmap.height
            if (bitmapSizes.size == 1) initialReceipt else detailedReceipt
        }

        val preview = assertReady(reader.read(FixtureImage.SELECTION))

        assertEquals(2, bitmapSizes.size)
        assertTrue(bitmapSizes[1].first > bitmapSizes[0].first)
        assertTrue(bitmapSizes[1].second > bitmapSizes[0].second)
        assertEquals(12_550L, preview.amountPaise)
        assertEquals("Synthetic Cafe", preview.merchant)
        assertEquals("2025-05-03T21:41", preview.dateTime.toString())
        assertEquals("T123456789012345678", preview.transactionId)
        assertEquals(sha256(fixture.bytes), preview.imageHash)
        assertTrue(fixture.streamClosed)
    }

    @Test
    fun thrownRetryKeepsInitialReadyWithRecognizedFieldsAndMissingAmount() = runBlocking {
        val fixture = FixtureImage(3000, 1200)
        val bitmapSizes = mutableListOf<Pair<Int, Int>>()
        val reader = fixture.reader { bitmap ->
            bitmapSizes += bitmap.width to bitmap.height
            if (bitmapSizes.size == 1) initialReceipt else error("synthetic detail failure")
        }

        val preview = assertReady(reader.read(FixtureImage.SELECTION))

        assertEquals(2, bitmapSizes.size)
        assertNull(preview.amountPaise)
        assertEquals("Synthetic Cafe", preview.merchant)
        assertEquals("2025-05-03T21:41", preview.dateTime.toString())
        assertEquals("T123456789012345678", preview.transactionId)
        assertTrue(fixture.streamClosed)
    }

    @Test
    fun eightMegapixelRetryCapSkipsDetailDecodeThatCannotIncreaseResolution() = runBlocking {
        val fixture = FixtureImage(3000, 3000)
        val bitmapSizes = mutableListOf<Pair<Int, Int>>()
        val reader = fixture.reader { bitmap ->
            bitmapSizes += bitmap.width to bitmap.height
            initialReceipt
        }

        val preview = assertReady(reader.read(FixtureImage.SELECTION))

        assertTrue(fixture.sourcePixels > ContentResolverReceiptImageReader.DETAIL_MAX_DECODED_PIXELS)
        assertEquals(1, bitmapSizes.size)
        assertNull(preview.amountPaise)
        assertTrue(fixture.streamClosed)
    }

    private fun assertReady(result: ReceiptImageReadResult): dev.snapbudget.application.ReceiptImagePreview {
        assertTrue("Expected Ready", result is ReceiptImageReadResult.Ready)
        return (result as ReceiptImageReadResult.Ready).preview
    }

    private class FixtureImage(width: Int, height: Int) {
        val sourcePixels = width.toLong() * height
        val bytes = createSolidPng(width, height)
        var streamClosed = false

        fun reader(recognize: (Bitmap) -> ReceiptTextLayout) =
            ContentResolverReceiptImageReader(
                mimeType = { uri -> "image/png".takeIf { uri == Uri.parse(SELECTION.value) } },
                openStream = { uri ->
                    if (uri != Uri.parse(SELECTION.value)) null
                    else object : FilterInputStream(ByteArrayInputStream(bytes)) {
                        override fun close() {
                            streamClosed = true
                            super.close()
                        }
                    }
                },
                recognizeForTest = { bitmap, onFinished ->
                    try {
                        recognize(bitmap)
                    } finally {
                        onFinished()
                    }
                },
            )

        companion object {
            val SELECTION = ReceiptImageSelection("content://fixture/resolution-retry")

            private fun createSolidPng(width: Int, height: Int): ByteArray {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).drawColor(Color.WHITE)
                    return java.io.ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val initialReceipt = ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nSynthetic Cafe\n9:41 PM on 03 MAY 2025\nTransaction ID: T123456789012345678",
            emptyList(),
        )
        val detailedReceipt = ReceiptTextLayout(
            "PhonePe\nTransaction Successful\nPaid to\nChanged Merchant\nINR 125.50",
            emptyList(),
        )
    }
}
