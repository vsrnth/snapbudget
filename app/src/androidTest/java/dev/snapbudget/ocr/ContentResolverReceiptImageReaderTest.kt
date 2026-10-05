package dev.snapbudget.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.snapbudget.ReceiptTextBlock
import dev.snapbudget.ReceiptTextLayout
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun rejectsUnknownAndMixedProviderLayouts() = runBlocking {
        val bytes = syntheticReceiptPng()
        val unknown = SyntheticResolver(bytes, "image/png").reader { _, done ->
            try {
                ReceiptTextLayout("Transaction Successful\nPaid to\nSynthetic Shop\nINR 123.45", emptyList())
            } finally { done() }
        }.read(ReceiptImageSelection("content://fixture/unknown"))
        assertEquals(ReceiptImageReadResult.Unsupported, unknown)

        val mixed = SyntheticResolver(bytes, "image/png").reader { _, done ->
            try {
                ReceiptTextLayout(
                    "PhonePe\nTransaction Successful\nPaid to\nSynthetic Shop\nGoogle Pay\nCompleted\nINR 123.45",
                    emptyList(),
                )
            } finally { done() }
        }.read(ReceiptImageSelection("content://fixture/mixed"))
        assertEquals(ReceiptImageReadResult.Unsupported, mixed)
    }

    @Test fun retriesLargeImageAtHigherResolutionAfterSampledCandidateConflict() = runBlocking {
        val bytes = syntheticReceiptPng(
            lines = listOf("PhonePe", "Transaction Successful", "Paid to", "Synthetic Bakery", "Transaction Details"),
            width = 4_200,
            height = 3_200,
        )
        val resolver = SyntheticResolver(bytes, "image/png")
        val bitmaps = mutableListOf<Bitmap>()
        val layouts = listOf(
            qualifiedLayout(listOf("245.68", "246.68")),
            qualifiedLayout(listOf("245.68", "245.68"), includeDebit = true),
        )
        val result = resolver.reader { bitmap, onFinished ->
            assertTrue("Prior OCR bitmap must be released before retry", bitmaps.lastOrNull()?.isRecycled != false)
            bitmaps += bitmap
            try { layouts[bitmaps.lastIndex] } finally { onFinished() }
        }.read(ReceiptImageSelection("content://fixture/large"))

        assertTrue(result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertEquals(24_568L, preview.amountPaise)
        assertEquals("T987654321098765432", preview.transactionId)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, preview.imageHash)
        assertEquals(1, resolver.openCount)
        assertEquals(2, bitmaps.size)
        assertTrue(bitmaps.all(Bitmap::isRecycled))
        assertTrue(bitmaps[1].width > bitmaps[0].width || bitmaps[1].height > bitmaps[0].height)
        assertTrue(bitmaps[1].width <= ContentResolverReceiptImageReader.DETAIL_MAX_DECODED_SIDE)
        assertTrue(bitmaps[1].height <= ContentResolverReceiptImageReader.DETAIL_MAX_DECODED_SIDE)
        assertTrue(bitmaps[1].width.toLong() * bitmaps[1].height <= ContentResolverReceiptImageReader.DETAIL_MAX_DECODED_PIXELS)
    }

    @Test fun detailConflictRetainsNullAmount() = runBlocking {
        val bytes = syntheticReceiptPng(width = 4_200, height = 3_200)
        val resolver = SyntheticResolver(bytes, "image/png")
        val layouts = listOf(
            qualifiedLayout(emptyList(), transactionId = "T11111111111111111"),
            qualifiedLayout(listOf("245.68", "246.68"), transactionId = "T11111111111111111"),
        )
        var calls = 0
        val result = resolver.reader { _, onFinished -> try { layouts[calls++] } finally { onFinished() } }
            .read(ReceiptImageSelection("content://fixture/conflict"))
        assertTrue(result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertNull(preview.amountPaise)
        assertEquals("T11111111111111111", preview.transactionId)
        assertEquals(2, calls)
        assertEquals(1, resolver.openCount)
    }

    @Test fun differingTransactionIdentityPreventsUsingDetailedAmount() = runBlocking {
        val resolver = SyntheticResolver(syntheticReceiptPng(width = 4_200, height = 3_200), "image/png")
        val layouts = listOf(
            qualifiedLayout(emptyList(), transactionId = "T11111111111111111"),
            qualifiedLayout(listOf("245.68"), transactionId = "T22222222222222222"),
        )
        var calls = 0
        val result = resolver.reader { _, onFinished -> try { layouts[calls++] } finally { onFinished() } }
            .read(ReceiptImageSelection("content://fixture/different-id"))
        assertTrue(result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertNull(preview.amountPaise)
        assertEquals("T11111111111111111", preview.transactionId)
        assertEquals(2, calls)
        assertEquals(1, resolver.openCount)
    }

    @Test fun crossProviderDetailCannotSupplyAmountWhenInitialTransactionIdIsMissing() = runBlocking {
        val resolver = SyntheticResolver(syntheticReceiptPng(width = 4_200, height = 3_200), "image/png")
        val layouts = listOf(
            ReceiptTextLayout(
                "PhonePe\nTransaction Successful\nPaid to\nSynthetic Cafe\n9:41 PM on 03 MAY 2025",
                emptyList(),
            ),
            ReceiptTextLayout(
                "Google Pay\nPayment of INR 125.50 completed\nCompleted\n3 May 2025, 8:22am\n" +
                    "UPI transaction ID\n123456789012\nTo: Synthetic Shop\nGoogle transaction ID\nGPA1234567890",
                emptyList(),
            ),
        )
        var calls = 0
        val result = resolver.reader { _, done -> try { layouts[calls++] } finally { done() } }
            .read(ReceiptImageSelection("content://fixture/cross-provider-detail"))

        assertTrue(result is ReceiptImageReadResult.Ready)
        val preview = (result as ReceiptImageReadResult.Ready).preview
        assertNull(preview.amountPaise)
        assertEquals("Synthetic Cafe", preview.merchant)
        assertEquals("2025-05-03T21:41", preview.dateTime.toString())
        assertNull(preview.transactionId)
        assertEquals(2, calls)
    }

    @Test fun retriesDoNotOccurForPresentAmountUnsupportedReceiptOrNoHigherResolution() = runBlocking {
        val largeBytes = syntheticReceiptPng(width = 4_200, height = 3_200)
        val presentResolver = SyntheticResolver(largeBytes, "image/png")
        var presentCalls = 0
        val present = presentResolver.reader { bitmap, done ->
            presentCalls++
            try { qualifiedLayout(listOf("245.68")) } finally { done() }
        }.read(ReceiptImageSelection("content://fixture/present"))
        assertTrue(present is ReceiptImageReadResult.Ready)
        assertEquals(1, presentCalls)
        assertEquals(1, presentResolver.openCount)

        val unsupportedResolver = SyntheticResolver(largeBytes, "image/png")
        var unsupportedCalls = 0
        val unsupported = unsupportedResolver.reader { _, done ->
            unsupportedCalls++
            try { ReceiptTextLayout(
                "PhonePe\nTransaction Failed\nPaid to\nSynthetic Bakery",
                qualifiedLayout(emptyList()).blocks,
            ) } finally { done() }
        }.read(ReceiptImageSelection("content://fixture/unsupported"))
        assertEquals(ReceiptImageReadResult.Unsupported, unsupported)
        assertEquals(1, unsupportedCalls)
        assertEquals(1, unsupportedResolver.openCount)

        val smallResolver = SyntheticResolver(syntheticReceiptPng(), "image/png")
        var smallCalls = 0
        val small = smallResolver.reader { _, done ->
            smallCalls++
            try { qualifiedLayout(emptyList()) } finally { done() }
        }.read(ReceiptImageSelection("content://fixture/small"))
        assertTrue(small is ReceiptImageReadResult.Ready)
        assertEquals(1, smallCalls)
        assertEquals(1, smallResolver.openCount)
    }

    @Test fun cancellationDuringDetailRetryPropagatesAndReleasesRetryBitmap() = runBlocking {
        val resolver = SyntheticResolver(syntheticReceiptPng(width = 4_200, height = 3_200), "image/png")
        val bitmaps = mutableListOf<Bitmap>()
        var calls = 0
        val secondRecognitionStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch {
            resolver.reader { bitmap, done ->
                calls++
                bitmaps += bitmap
                if (calls == 1) {
                    try { qualifiedLayout(emptyList()) } finally { done() }
                } else {
                    secondRecognitionStarted.complete(Unit)
                    try { awaitCancellation() } finally { done() }
                }
            }.read(ReceiptImageSelection("content://fixture/cancel-retry"))
        }
        try {
            withTimeout(5_000) { secondRecognitionStarted.await() }
        } finally {
            job.cancelAndJoin()
        }
        assertEquals(2, calls)
        assertTrue(job.isCancelled)
        assertTrue(bitmaps.all(Bitmap::isRecycled))
        assertEquals(1, resolver.openCount)
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

    private fun syntheticReceiptPng(
        lines: List<String> = listOf(
            "PhonePe", "Transaction Successful", "Paid to", "Synthetic Cafe", "INR 125.50",
            "9:41 PM on 03 MAY 2025", "Transaction ID: T123456789012345678",
        ),
        width: Int = 1200,
        height: Int = 1200,
    ): ByteArray {
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

    private fun qualifiedLayout(
        amounts: List<String>,
        includeDebit: Boolean = false,
        transactionId: String = "T987654321098765432",
    ): ReceiptTextLayout {
        val lines = buildList {
            add("PhonePe")
            add("Transaction Successful")
            add("Paid to")
            add("Synthetic Bakery")
            amounts.firstOrNull()?.let(::add)
            if (includeDebit) {
                add("Debited from")
                add("Synthetic Wallet")
                amounts.getOrNull(1)?.let(::add)
            } else amounts.drop(1).forEach(::add)
            add("Transaction Details")
            add("Transaction ID: $transactionId")
        }
        val blocks = buildList {
            add(textBlock("Paid to", 0.10f, 0.10f))
            add(textBlock("Synthetic Bakery", 0.10f, 0.13f))
            amounts.firstOrNull()?.let { add(textBlock(it, 0.10f, 0.16f)) }
            if (includeDebit) {
                add(textBlock("Debited from", 0.10f, 0.40f))
                add(textBlock("Synthetic Wallet", 0.10f, 0.43f))
                amounts.getOrNull(1)?.let { add(textBlock(it, 0.10f, 0.46f)) }
            } else amounts.drop(1).forEachIndexed { index, value -> add(textBlock(value, 0.10f, 0.20f + index * 0.03f)) }
            add(textBlock("Transaction Details", 0.10f, 0.70f))
        }
        return ReceiptTextLayout(lines.joinToString("\n"), blocks)
    }

    private fun textBlock(text: String, left: Float, top: Float) = ReceiptTextBlock(
        text, left, top, left + 0.3f, top + 0.02f,
    )

    private class SyntheticResolver(var bytes: ByteArray, var mime: String) {
        var openCount = 0
        var lastStreamClosed = false

        fun reader(recognize: (suspend (Bitmap, () -> Unit) -> ReceiptTextLayout)? = null) = ContentResolverReceiptImageReader(
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
            recognizeForTest = recognize,
        )
    }
}
