package dev.snapbudget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDateTime

class ExpenseTest {
    @Test fun parsesExactPositivePaiseAndRejectsInvalidOrOverflowingAmounts() {
        assertEquals(1L, parsePositivePaise("0.01"))
        assertEquals(120L, parsePositivePaise("1.2"))
        assertEquals(12_550L, parsePositivePaise("125.50"))
        assertNull(parsePositivePaise("0"))
        assertNull(parsePositivePaise("-1"))
        assertNull(parsePositivePaise("1.001"))
        assertNull(parsePositivePaise("1,000"))
        assertNull(parsePositivePaise("92233720368547758.08"))
    }

    @Test fun trimsAndBoundsManualFieldsAndStrictlyParsesLocalDateTime() {
        val draft = ManualExpenseDraft.createWithToken("12.00", " Cafe ", "2024-02-29T21:41", " Food ", "manual:stable")
        assertNotNull(draft)
        assertEquals("Cafe", draft!!.merchant)
        assertEquals("Food", draft.category)
        assertEquals(LocalDateTime.of(2024, 2, 29, 21, 41), draft.dateTime)
        assertNull(ManualExpenseDraft.createWithToken("12", " ", "2024-01-01T00:00", "Food", "manual:x"))
        assertNull(ManualExpenseDraft.createWithToken("12", "Cafe", "2023-02-29T00:00", "Food", "manual:x"))
        assertNull(ManualExpenseDraft.createWithToken("12", "Cafe", "2024-01-01T00:00:01", "Food", "manual:x"))
        assertNull(ManualExpenseDraft.createWithToken("12", "x".repeat(121), "2024-01-01T00:00", "Food", "manual:x"))
        assertNull(ManualExpenseDraft.createWithToken("12", "Cafe", "2024-01-01T00:00", "x".repeat(61), "manual:x"))
        assertNull(ManualExpenseDraft.createWithToken("12", "Cafe", "2024-01-01T00:00", "Food", "hash"))
    }

    @Test fun totalsDoNotOverflowAndInrOutputIsDeterministic() {
        val one = ExpenseRecord(1, Long.MAX_VALUE, "A", LocalDateTime.MIN, "B")
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), totalPaise(listOf(one, one)))
        assertEquals("₹1,23,45,678.90", formatInr(12_345_678_90L))
        assertEquals("-₹1,23,45,678.90", formatInr(BigInteger.valueOf(-12_345_678_90L)))
        assertEquals("₹0.01", formatInr(1L))
    }
}
