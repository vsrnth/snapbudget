package dev.snapbudget

/** Transient OCR text geometry, normalized to the image's [0, 1] coordinate space. */
data class ReceiptTextLayout(
    val text: String,
    val blocks: List<ReceiptTextBlock>,
)

data class ReceiptTextBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val parentText: String? = null,
)
