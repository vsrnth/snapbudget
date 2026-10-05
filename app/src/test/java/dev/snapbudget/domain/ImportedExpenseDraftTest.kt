package dev.snapbudget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDateTime

class ImportedExpenseDraftTest {
    private val hash = "a".repeat(64)

    @Test fun validatesCorrectionsAndImmutableOriginalIdentities() {
        val draft = ImportedExpenseDraft.create(
            amountPaise = 12_550L,
            merchant = " Synthetic Cafe ",
            dateTime = "2025-05-03T21:41",
            category = " Other ",
            imageHash = hash.uppercase(),
            transactionId = "T123456789012345",
        )
        assertNotNull(draft)
        assertEquals(12_550L, draft!!.amountPaise)
        assertEquals("Synthetic Cafe", draft.merchant)
        assertEquals(LocalDateTime.of(2025, 5, 3, 21, 41), draft.dateTime)
        assertEquals("Other", draft.category)
        assertEquals(hash, draft.imageHash)
        assertEquals("T123456789012345", draft.transactionId)
        val corrected = draft.withCorrections(99_999L, "Updated Merchant", "2025-05-04T09:15", "Updated")!!
        assertEquals(99_999L, corrected.amountPaise)
        assertEquals("Updated Merchant", corrected.merchant)
        assertEquals(hash, corrected.imageHash)
        assertEquals("T123456789012345", corrected.transactionId)
        assertNull(draft.withCorrections(0L, "Updated Merchant", "2025-05-04T09:15", "Updated"))

        assertNull(create(amount = 0))
        assertNull(create(merchant = " "))
        assertNull(create(merchant = "m".repeat(121)))
        assertNull(create(category = " "))
        assertNull(create(category = "c".repeat(61)))
        assertNull(create(dateTime = "2025-02-29T12:00"))
        assertNull(create(dateTime = "2025-05-03T21:41:00"))
        assertNull(create(imageHash = "abc"))
        assertNull(create(transactionId = "T123"))
        assertNull(create(transactionId = " T123456789012345"))
        assertNull(create(transactionId = "T" + "1".repeat(36)))
        assertNotNull(create(transactionId = "GPAY:UPI:123456789012"))
        assertNotNull(create(transactionId = "GPAY:GOOGLE:Abc_123-xY"))
        assertNull(create(transactionId = "GPAY:UPI:12345678901"))
        assertNull(create(transactionId = "GPAY:UPI:1234567890123"))
        assertNull(create(transactionId = "GPAY:GOOGLE:abc"))
        assertNull(create(transactionId = "GPAY:GOOGLE:" + "x".repeat(129)))
        assertNull(create(transactionId = "GPAY:UNTRUSTED:123456789012"))
        assertNull(create(transactionId = "gpay:UPI:123456789012"))
    }

    @Test fun preservesCanonicalGooglePayIdentityAndOpaqueIdCaseAcrossCorrections() {
        for (transactionId in listOf("GPAY:UPI:123456789012", "GPAY:GOOGLE:AbC_xY-123")) {
            val draft = create(transactionId = transactionId)!!
            val corrected = draft.withCorrections(9_999L, "Synthetic Store", "2025-05-04T09:15", "Other")!!
            assertEquals(transactionId, corrected.transactionId)
            assertEquals(hash, corrected.imageHash)
        }
    }

    private fun create(
        amount: Long = 1L,
        merchant: String = "Cafe",
        dateTime: String = "2025-05-03T21:41",
        category: String = "Other",
        imageHash: String = hash,
        transactionId: String? = null,
    ) = ImportedExpenseDraft.create(amount, merchant, dateTime, category, imageHash, transactionId)
}
