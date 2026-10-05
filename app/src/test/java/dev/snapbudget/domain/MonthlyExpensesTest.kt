package dev.snapbudget.domain

import java.time.LocalDateTime
import java.time.YearMonth
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyExpensesTest {
    @Test fun filtersByMonthAndYearIncludingLeapDayAndSumsExactPaise() {
        val records = listOf(
            ExpenseRecord(1, 125, "A", LocalDateTime.parse("2024-02-01T00:00"), "Other"),
            ExpenseRecord(2, 75, "B", LocalDateTime.parse("2024-02-29T23:59"), "Other"),
            ExpenseRecord(3, 999, "C", LocalDateTime.parse("2024-03-01T00:00"), "Other"),
            ExpenseRecord(4, 999, "D", LocalDateTime.parse("2025-02-01T00:00"), "Other"),
        )

        val summary = monthlyExpenses(records, YearMonth.of(2024, 2))

        assertEquals(listOf(1L, 2L), summary.records.map(ExpenseRecord::id))
        assertEquals(2, summary.count)
        assertEquals("₹2.00", formatInr(summary.totalPaise))
    }

    @Test fun emptyMonthHasZeroTotalAndCount() {
        val summary = monthlyExpenses(emptyList(), YearMonth.of(2026, 10))

        assertEquals(0, summary.count)
        assertEquals("₹0.00", formatInr(summary.totalPaise))
    }

    @Test fun exactMonthlyTotalCanExceedLongRange() {
        val amount = Long.MAX_VALUE
        val records = listOf(
            ExpenseRecord(1, amount, "A", LocalDateTime.parse("2024-02-01T00:00"), "Other"),
            ExpenseRecord(2, amount, "B", LocalDateTime.parse("2024-02-02T00:00"), "Other"),
        )

        assertEquals(BigInteger.valueOf(amount).multiply(BigInteger.TWO), monthlyExpenses(records, YearMonth.of(2024, 2)).totalPaise)
    }
}
