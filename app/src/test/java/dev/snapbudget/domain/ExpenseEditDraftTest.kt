package dev.snapbudget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseEditDraftTest {
    @Test fun createsTrimmedExactDraft() {
        val draft = ExpenseEditDraft.create(9, "125.5", " Synthetic Cafe ", "2025-05-03T21:41", " Other ")!!
        assertEquals(9L, draft.id)
        assertEquals(12_550L, draft.amountPaise)
        assertEquals("Synthetic Cafe", draft.merchant)
        assertEquals("Other", draft.category)
        assertEquals("2025-05-03T21:41", draft.dateTime.toString())
    }

    @Test fun rejectsInvalidIdentityAmountTextLengthsAndDate() {
        assertNull(ExpenseEditDraft.create(0, "1", "Cafe", "2025-05-03T21:41", "Other"))
        assertNull(ExpenseEditDraft.create(-1, "1", "Cafe", "2025-05-03T21:41", "Other"))
        listOf("0", "-1", "1.001", "1,000", "92233720368547758.08").forEach {
            assertNull(ExpenseEditDraft.create(1, it, "Cafe", "2025-05-03T21:41", "Other"))
        }
        assertNull(ExpenseEditDraft.create(1, "1", " ", "2025-05-03T21:41", "Other"))
        assertNull(ExpenseEditDraft.create(1, "1", "m".repeat(121), "2025-05-03T21:41", "Other"))
        assertNull(ExpenseEditDraft.create(1, "1", "Cafe", "2025-05-03T21:41", " "))
        assertNull(ExpenseEditDraft.create(1, "1", "Cafe", "2025-05-03T21:41", "c".repeat(61)))
        assertNull(ExpenseEditDraft.create(1, "1", "Cafe", "2025-02-30T21:41", "Other"))
        assertNull(ExpenseEditDraft.create(1, "1", "Cafe", "2025-5-3T21:41", "Other"))
    }
}
