package dev.snapbudget.domain

import java.time.YearMonth
import java.math.BigInteger

/** Summary of records whose local expense date falls in one calendar month. */
data class MonthlyExpenses(val records: List<ExpenseRecord>, val totalPaise: BigInteger) {
    val count: Int get() = records.size
}

fun monthlyExpenses(records: List<ExpenseRecord>, month: YearMonth): MonthlyExpenses {
    val matching = records.filter { YearMonth.from(it.dateTime) == month }
    return MonthlyExpenses(matching, totalPaise(matching))
}
