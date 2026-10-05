package dev.snapbudget.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.snapbudget.ExpenseDatabase
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ImportedExpenseDraft
import dev.snapbudget.domain.ManualExpenseDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImportedExpenseRoomRepositoryTest {
    @Test fun receiptHashAndTransactionIdentityDeduplicateAndManualWritesRemainCompatible() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "imported-expense-${UUID.randomUUID()}.db"
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val hashC = "c".repeat(64)
        val id: Long
        try {
            val first = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            try {
                val repository = RoomExpenseRepository(first.expenses())
                val firstDraft = draft(hashA, "T123456789012345", merchant = "Original Merchant")
                val inserted = repository.addImportedExpense(firstDraft)
                assertTrue(inserted is AddExpenseResult.Inserted)
                id = (inserted as AddExpenseResult.Inserted).id
                assertEquals(
                    AddExpenseResult.Duplicate,
                    repository.addImportedExpense(draft(hashA, "T123456789012346", amount = 99_999L, merchant = "Corrected Merchant")),
                )
                assertEquals(
                    AddExpenseResult.Duplicate,
                    repository.addImportedExpense(draft(hashB, "T123456789012345")),
                )
                assertTrue(repository.addImportedExpense(draft(hashB, "T223456789012345")) is AddExpenseResult.Inserted)
                assertTrue(repository.addImportedExpense(draft(hashC, null)) is AddExpenseResult.Inserted)

                val manualDraft = ManualExpenseDraft.createWithToken(
                    "4.25", "Manual Cafe", "2025-05-04T12:30", "Other", "manual:import-compat-${UUID.randomUUID()}",
                )!!
                assertTrue(repository.addManualExpense(manualDraft) is AddExpenseResult.Inserted)
                assertEquals(AddExpenseResult.Duplicate, repository.addManualExpense(manualDraft))
                val rows = withTimeout(3_000) { repository.observeExpenses().first() } as ExpenseObservation.Records
                assertEquals(4, rows.expenses.size)
                val original = rows.expenses.single { it.id == id }
                assertEquals(12_550L, original.amountPaise)
                assertEquals("Original Merchant", original.merchant)
                val storedOriginal = withTimeout(3_000) { first.expenses().observe().first() }.single { it.id == id }
                assertEquals(hashA, storedOriginal.imageHash)
                assertEquals("T123456789012345", storedOriginal.transactionId)
                assertEquals("2025-05-03T21:41", storedOriginal.dateTime)
                assertEquals("Other", storedOriginal.category)
            } finally {
                first.close()
            }

            val reopened = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            try {
                val rows = withTimeout(3_000) { RoomExpenseRepository(reopened.expenses()).observeExpenses().first() } as ExpenseObservation.Records
                assertEquals(4, rows.expenses.size)
                assertTrue(rows.expenses.any { it.id == id && it.merchant == "Original Merchant" })
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
            assertFalse(context.getDatabasePath(name).exists())
            assertFalse(context.getDatabasePath("$name-wal").exists())
            assertFalse(context.getDatabasePath("$name-shm").exists())
        }
    }

    private fun draft(hash: String, transactionId: String?, amount: Long = 12_550L, merchant: String = "Synthetic Cafe") =
        ImportedExpenseDraft.create(amount, merchant, "2025-05-03T21:41", "Other", hash, transactionId)!!
}
