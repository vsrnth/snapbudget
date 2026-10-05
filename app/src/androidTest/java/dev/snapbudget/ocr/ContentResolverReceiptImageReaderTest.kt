package dev.snapbudget.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ContentResolverReceiptImageReaderTest {
    @Test fun rejectsNonContentUrisAndUnsupportedMimeWithoutOpeningStream() = runBlocking {
        val resolver = SyntheticResolver(byteArrayOf(), "text/plain")
        val reader = resolver.reader()
        assertEquals(ReceiptImageReadResult.InvalidSelection, reader.read(ReceiptImageSelection("file:///tmp/image.png")))
        assertEquals(ReceiptImageReadResult.Unsupported, reader.read(ReceiptImageSelection("content://fixture/item")))
        assertEquals(0, resolver.openCount)
    }

    @Test fun rejectsCorruptAndOversizedImagesAndClosesStreams() = runBlocking {
        val resolver = SyntheticResolver("not an image".toByteArray(), "image/png")
        val reader = resolver.reader()
        assertEquals(ReceiptImageReadResult.Unreadable, reader.read(ReceiptImageSelection("content://fixture/corrupt")))
        assertTrue(resolver.lastStreamClosed)

        resolver.bytes = ByteArray(ContentResolverReceiptImageReader.MAX_IMAGE_BYTES + 1)
        assertEquals(ReceiptImageReadResult.Unsupported, reader.read(ReceiptImageSelection("content://fixture/large")))
        assertTrue(resolver.lastStreamClosed)
    }

    @Test fun readsGeneratedPhonePeReceiptAndReturnsTypedParseAndOriginalByteHash() = runBlocking {
        val bytes = syntheticReceiptPng()
        val resolver = SyntheticResolver(bytes, "image/png")
        val result = resolver.reader().read(ReceiptImageSelection("content://fixture/receipt"))
        assertTrue("Expected typed ready result, got ${result::class.simpleName}", result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertEquals(12_550L, preview.amountPaise)
        assertEquals("Synthetic Cafe", preview.merchant)
        assertEquals("T123456789012345678", preview.transactionId)
        assertEquals("2025-05-03T21:41", preview.dateTime.toString())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, preview.imageHash)
        assertTrue(resolver.lastStreamClosed)
    }

    @Test fun readsSyntheticCurrencylessAmountBesidePayeeDespiteUnrelatedDetailNumbers() = runBlocking {
        val bytes = syntheticReceiptPng(listOf(
            "PhonePe", "Transaction Successful", "Paid to", "Synthetic Bakery", "245.68",
            "Transaction Details", "Reference No 9876543210", "Account Number 12345678",
            "Phone 9123456780", "Transaction ID: T987654321098765432",
        ))
        val resolver = SyntheticResolver(bytes, "image/png")
        val result = resolver.reader().read(ReceiptImageSelection("content://fixture/receipt"))
        assertTrue("Expected typed ready result, got ${result::class.simpleName}", result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertEquals(24_568L, preview.amountPaise)
        assertEquals("Synthetic Bakery", preview.merchant)
        assertEquals("T987654321098765432", preview.transactionId)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, preview.imageHash)
    }

    @Test fun rejectsFailedIncomingAndNonPhonePeReceiptText() = runBlocking {
        for (text in listOf(
            listOf("PhonePe", "Transaction Failed", "Paid to", "Synthetic Cafe", "INR 125.50"),
            listOf("PhonePe", "Transaction Successful", "Received from", "Synthetic Cafe", "INR 125.50"),
            listOf("Other App", "Transaction Successful", "Paid to", "Synthetic Cafe", "INR 125.50"),
        )) {
            val resolver = SyntheticResolver(syntheticReceiptPng(text), "image/png")
            assertEquals(ReceiptImageReadResult.Unsupported, resolver.reader()
                .read(ReceiptImageSelection("content://fixture/receipt")))
        }
    }

    @Test fun cancellationBeforeReadDoesNotOpenProviderStream() {
        val resolver = SyntheticResolver(byteArrayOf(), "image/png")
        val reader = resolver.reader()
        val job = kotlinx.coroutines.Job().apply { cancel() }
        try {
            runBlocking(job) { reader.read(ReceiptImageSelection("content://fixture/item")) }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, resolver.openCount)
        }
    }

    private fun syntheticReceiptPng(lines: List<String> = listOf(
        "PhonePe", "Transaction Successful", "Paid to", "Synthetic Cafe", "INR 125.50",
        "9:41 PM on 03 MAY 2025", "Transaction ID: T123456789012345678",
    )): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
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

    private class SyntheticResolver(var bytes: ByteArray, var mime: String) {
        var openCount = 0
        var lastStreamClosed = false

        fun reader() = ContentResolverReceiptImageReader(
            mimeType = { mime },
            openStream = {
                openCount++
                lastStreamClosed = false
                object : ByteArrayInputStream(bytes) {
                    override fun close() {
                        lastStreamClosed = true
                        super.close()
                    }
                }
            },
        )
    }
}
