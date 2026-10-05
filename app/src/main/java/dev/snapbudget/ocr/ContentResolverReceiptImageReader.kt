package dev.snapbudget.ocr

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dev.snapbudget.Receipt
import dev.snapbudget.ReceiptParser
import dev.snapbudget.ReceiptTextBlock
import dev.snapbudget.ReceiptTextLayout
import dev.snapbudget.application.ReceiptImagePreview
import dev.snapbudget.application.ReceiptImageReadResult
import dev.snapbudget.application.ReceiptImageReader
import dev.snapbudget.application.ReceiptImageSelection
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.coroutines.resume

/** Reads only bounded image bytes from a user-selected content URI and performs bundled, on-device OCR. */
class ContentResolverReceiptImageReader internal constructor(
    private val mimeType: (Uri) -> String?,
    private val openStream: (Uri) -> java.io.InputStream?,
    private val recognizeForTest: (suspend (Bitmap, () -> Unit) -> ReceiptTextLayout)? = null,
) : ReceiptImageReader {
    constructor(context: Context) : this(
        context.applicationContext.contentResolver::getType,
        context.applicationContext.contentResolver::openInputStream,
    )

    override suspend fun read(selection: ReceiptImageSelection): ReceiptImageReadResult {
        val uri = selection.value.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: return ReceiptImageReadResult.InvalidSelection
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            return ReceiptImageReadResult.InvalidSelection
        }
        return try {
            withContext(Dispatchers.IO) {
                val mime = mimeType(uri)?.lowercase(Locale.ROOT)
                    ?: return@withContext ReceiptImageReadResult.Unsupported
                if (mime !in SUPPORTED_MIME_TYPES) return@withContext ReceiptImageReadResult.Unsupported
                val bytes = readBounded(uri) ?: return@withContext ReceiptImageReadResult.Unsupported
                val dimensions = decodeBounds(bytes)
                    ?: return@withContext ReceiptImageReadResult.Unreadable
                val bitmap = decodeSampled(
                    bytes,
                    dimensions.first,
                    dimensions.second,
                    MAX_DECODED_SIDE,
                    MAX_DECODED_PIXELS,
                ) ?: return@withContext ReceiptImageReadResult.Unreadable
                val oriented = try {
                    orient(bitmap, bytes)
                } catch (failure: Exception) {
                    bitmap.recycle()
                    throw failure
                }
                val initialResolution = oriented.width.toLong() * oriented.height
                val ocrStartedAt = System.nanoTime()
                val firstLayout = withTimeout(OCR_TIMEOUT_MILLIS) {
                    performRecognition(oriented) { recyclePair(oriented, bitmap) }
                }
                val initialParsed = ReceiptParser.parse(firstLayout)
                if (!initialParsed.eligible || !firstLayout.text.contains("PhonePe", ignoreCase = true)) {
                    ReceiptImageReadResult.Unsupported
                } else {
                    val selected = if (initialParsed.amountPaise != null) {
                        initialParsed
                    } else {
                        parseHigherResolutionIfAvailable(
                            bytes = bytes,
                            sourceWidth = dimensions.first,
                            sourceHeight = dimensions.second,
                            initialResolution = initialResolution,
                            initialParsed = initialParsed,
                            ocrStartedAtNanos = ocrStartedAt,
                        ) ?: initialParsed
                    }
                    ReceiptImageReadResult.Ready(selected.toPreview(sha256(bytes)))
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            ReceiptImageReadResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ReceiptImageReadResult.Unreadable
        } catch (_: Exception) {
            ReceiptImageReadResult.Unreadable
        }
    }

    private suspend fun readBounded(uri: Uri): ByteArray? {
        val stream = openStream(uri) ?: return null
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_IMAGE_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun Text.toReceiptTextLayout(width: Int, height: Int): ReceiptTextLayout {
        fun block(value: String, box: android.graphics.Rect?, parentText: String? = null): ReceiptTextBlock? {
            if (box == null || width <= 0 || height <= 0) return null
            return ReceiptTextBlock(
                text = value,
                left = (box.left / width.toFloat()).coerceIn(0f, 1f),
                top = (box.top / height.toFloat()).coerceIn(0f, 1f),
                right = (box.right / width.toFloat()).coerceIn(0f, 1f),
                bottom = (box.bottom / height.toFloat()).coerceIn(0f, 1f),
                parentText = parentText,
            )
        }
        val blocks = buildList {
            textBlocks.forEach { textBlock ->
                textBlock.lines.forEach { line ->
                    block(line.text, line.boundingBox)?.let(::add)
                    line.elements.forEach { element -> block(element.text, element.boundingBox, line.text)?.let(::add) }
                }
            }
        }
        return ReceiptTextLayout(text, blocks)
    }

    private suspend fun performRecognition(bitmap: Bitmap, onFinished: () -> Unit): ReceiptTextLayout =
        recognizeForTest?.invoke(bitmap, onFinished) ?: recognize(bitmap, onFinished)

    private suspend fun recognize(bitmap: Bitmap, onFinished: () -> Unit): ReceiptTextLayout {
        val recognizer = try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (failure: Exception) {
            onFinished()
            throw failure
        }
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) {
                try {
                    recognizer.close()
                } finally {
                    onFinished()
                }
            }
        }
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (failure: Exception) {
            release()
            throw failure
        }
        return suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                val layout = runCatching { result.toReceiptTextLayout(bitmap.width, bitmap.height) }
                release()
                if (continuation.isActive) continuation.resumeWith(layout)
            }.addOnFailureListener { failure ->
                release()
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            }.addOnCanceledListener {
                release()
                if (continuation.isActive) continuation.cancel()
            }
        }
    }

    private fun recyclePair(oriented: Bitmap, decoded: Bitmap) {
        if (oriented !== decoded && !oriented.isRecycled) oriented.recycle()
        if (!decoded.isRecycled) decoded.recycle()
    }

    private fun Receipt.toPreview(imageHash: String) = ReceiptImagePreview(
        amountPaise = amountPaise,
        merchant = merchant.takeIf(String::isNotBlank),
        dateTime = dateTime,
        imageHash = imageHash,
        transactionId = transactionId,
    )

    private suspend fun parseHigherResolutionIfAvailable(
        bytes: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        initialResolution: Long,
        initialParsed: Receipt,
        ocrStartedAtNanos: Long,
    ): Receipt? {
        val elapsedMillis = (System.nanoTime() - ocrStartedAtNanos) / NANOS_PER_MILLI
        val remainingMillis = OCR_TIMEOUT_MILLIS - elapsedMillis
        if (remainingMillis <= 0) return null
        return try {
            withTimeoutOrNull(remainingMillis) {
                val decoded = decodeSampled(
                    bytes,
                    sourceWidth,
                    sourceHeight,
                    DETAIL_MAX_DECODED_SIDE,
                    DETAIL_MAX_DECODED_PIXELS,
                ) ?: return@withTimeoutOrNull null
                if (decoded.width.toLong() * decoded.height <= initialResolution) {
                    decoded.recycle()
                    return@withTimeoutOrNull null
                }
                val oriented = try {
                    orient(decoded, bytes)
                } catch (failure: Exception) {
                    decoded.recycle()
                    throw failure
                }
                if (oriented.width.toLong() * oriented.height <= initialResolution) {
                    recyclePair(oriented, decoded)
                    return@withTimeoutOrNull null
                }
                val detailedLayout = performRecognition(oriented) { recyclePair(oriented, decoded) }
                val detailed = ReceiptParser.parse(detailedLayout)
                if (!detailed.eligible || !detailedLayout.text.contains("PhonePe", ignoreCase = true) ||
                    detailed.amountPaise == null
                ) return@withTimeoutOrNull null
                val initialId = initialParsed.transactionId
                val detailedId = detailed.transactionId
                if (initialId != null && detailedId != null && initialId != detailedId) null
                else initialParsed.copy(amountPaise = detailed.amountPaise)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION ||
            width.toLong() * height > MAX_SOURCE_PIXELS
        ) return null
        return width to height
    }

    private fun decodeSampled(
        bytes: ByteArray,
        width: Int,
        height: Int,
        maxSide: Int,
        maxPixels: Long,
    ): Bitmap? {
        var sample = 1
        fun sampled(value: Int) = ((value.toLong() + sample - 1) / sample).toInt()
        while (sampled(width) > maxSide || sampled(height) > maxSide ||
            sampled(width).toLong() * sampled(height) > maxPixels
        ) sample *= 2
        val bitmap = BitmapFactory.decodeStream(
            ByteArrayInputStream(bytes), null, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        if (bitmap.width > maxSide || bitmap.height > maxSide || bitmap.width.toLong() * bitmap.height > maxPixels) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun orient(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(90f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(270f) }
                else -> return bitmap
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_DECODED_SIDE = 2048
        internal const val DETAIL_MAX_DECODED_SIDE = 4096
        internal const val DETAIL_MAX_DECODED_PIXELS = 8_000_000L
        private const val MAX_DECODED_PIXELS = MAX_DECODED_SIDE.toLong() * MAX_DECODED_SIDE
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_SOURCE_DIMENSION = 12_000
        private const val MAX_SOURCE_PIXELS = 100_000_000L
        private const val OCR_TIMEOUT_MILLIS = 20_000L
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
    }
}
