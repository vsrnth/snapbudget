package dev.snapbudget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReceiptOcrTest {
    @Test fun bundledOcrParsesSyntheticReceiptAndPersistsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = syntheticReceiptBitmap()
        val result = AtomicReference<String?>()
        val failure = AtomicReference<Exception?>()
        val latch = CountDownLatch(1)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result.set(it.text); latch.countDown() }
                .addOnFailureListener { failure.set(it); latch.countDown() }
            assertTrue("Bundled ML Kit OCR exceeded 20 seconds", latch.await(20, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Bundled OCR failed", it) }
            val recognized = result.get().orEmpty()
            assertTrue("OCR missed receipt amount: $recognized", recognized.contains("125.50"))
            val parsed = ReceiptParser.parse(recognized)
            assertEquals(12_550L, parsed.amountPaise)
            assertTrue("OCR missed merchant: $recognized", parsed.merchant.contains("Synthetic Cafe", ignoreCase = true))
            assertEquals("T123456789012345678", parsed.transactionId)
            assertTrue(parsed.eligible)

            val databaseName = "ocr-${UUID.randomUUID()}.db"
            try {
                val database = androidx.room.Room.databaseBuilder(context, ExpenseDatabase::class.java, databaseName).build()
                try {
                    val inserted = runBlocking {
                        database.expenses().insert(
                            Expense(
                                amountPaise = parsed.amountPaise!!,
                                merchant = parsed.merchant,
                                dateTime = parsed.dateTime?.toString().orEmpty(),
                                category = "Other",
                                transactionId = parsed.transactionId,
                                imageHash = "synthetic-image-hash",
                            ),
                        )
                    }
                    assertTrue(inserted > 0)
                    val stored = runBlocking { withTimeout(3_000) { database.expenses().observe().first() } }.single()
                    assertEquals(12_550L, stored.amountPaise)
                    assertEquals("Synthetic Cafe", stored.merchant)
                    assertEquals("T123456789012345678", stored.transactionId)
                    // This final contract assertion intentionally reports the known parser date bug.
                    assertNotNull("Parsed receipt date missing; see docs/testing.md", parsed.dateTime)
                    assertEquals("2025-05-03T21:41", stored.dateTime)
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun syntheticReceiptBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        listOf(
            "PhonePe", "Transaction Successful", "Paid to", "Synthetic Cafe", "INR 125.50",
            "9:41 PM on 03 MAY 2025", "Transaction ID: T123456789012345678",
        ).forEachIndexed { index, line -> canvas.drawText(line, 45f, 100f + index * 105f, paint) }
        return bitmap
    }
}
