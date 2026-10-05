package dev.snapbudget.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.snapbudget.ExpenseDatabase
import dev.snapbudget.domain.AddExpenseResult
import dev.snapbudget.domain.ExpenseObservation
import dev.snapbudget.domain.ExpenseEditDraft
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
class RoomExpenseRepositoryTest {
    @Test fun editUpdatesFieldsOrderAndPersistsWithoutChangingManualOperationIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "manual-expense-edit-${UUID.randomUUID()}.db"
        val token = "manual:edit-${UUID.randomUUID()}"
        val earlier = ManualExpenseDraft.createWithToken("5", "First Cafe", "2025-05-01T09:00", "Other", token)!!
        val edit: ExpenseEditDraft
        val firstId: Long
        try {
            val database = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            try {
                val repository = RoomExpenseRepository(database.expenses())
                val later = ManualExpenseDraft.createWithToken("7", "Second Cafe", "2025-05-02T09:00", "Other", "manual:other-${UUID.randomUUID()}")!!
                firstId = (repository.addManualExpense(earlier) as AddExpenseResult.Inserted).id
                val secondId = (repository.addManualExpense(later) as AddExpenseResult.Inserted).id
                assertEquals(listOf(secondId, firstId), (withTimeout(3_000) { repository.observeExpenses().first() } as ExpenseObservation.Records).expenses.map { it.id })

                edit = ExpenseEditDraft.create(firstId, "19.25", " Edited Cafe ", "2025-05-03T11:30", "Food")!!
                assertEquals(dev.snapbudget.domain.UpdateExpenseResult.Updated, repository.updateExpense(edit))
                val updated = withTimeout(3_000) { repository.observeExpenses().first() as ExpenseObservation.Records }
                assertEquals(listOf(firstId, secondId), updated.expenses.map { it.id })
                assertEquals(1_925L, updated.expenses.first().amountPaise)
                assertEquals("Edited Cafe", updated.expenses.first().merchant)
                assertEquals("Food", updated.expenses.first().category)
                assertEquals("2025-05-03T11:30", updated.expenses.first().dateTime.toString())
                val stored = withTimeout(3_000) { database.expenses().observe().first() }.single { it.id == firstId }
                assertEquals(token, stored.imageHash)
                assertEquals(null, stored.transactionId)
                assertEquals(AddExpenseResult.Duplicate, repository.addManualExpense(earlier))
            } finally {
                database.close()
            }

            val reopened = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            try {
                val repository = RoomExpenseRepository(reopened.expenses())
                val persisted = (withTimeout(3_000) { repository.observeExpenses().first() } as ExpenseObservation.Records).expenses
                assertEquals(listOf("Edited Cafe", "Second Cafe"), persisted.map { it.merchant })
                assertEquals(AddExpenseResult.Duplicate, repository.addManualExpense(earlier))
                assertTrue(repository.deleteExpense(firstId))
                assertEquals(dev.snapbudget.domain.UpdateExpenseResult.Missing, repository.updateExpense(edit))
                assertEquals(listOf("Second Cafe"), (withTimeout(3_000) { repository.observeExpenses().first() } as ExpenseObservation.Records).expenses.map { it.merchant })
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

    @Test fun insertRetryReopenObserveAndDeleteUseLocalRoomDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "manual-expense-${UUID.randomUUID()}.db"
        val draft = ManualExpenseDraft.createWithToken("125.50", " Synthetic Cafe ", "2025-05-03T21:41", " Other ", "manual:test-${UUID.randomUUID()}")!!
        try {
            val first = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            val id = try {
                val repository = RoomExpenseRepository(first.expenses())
                val inserted = repository.addManualExpense(draft)
                assertTrue(inserted is AddExpenseResult.Inserted)
                val expenseId = (inserted as AddExpenseResult.Inserted).id
                assertEquals(AddExpenseResult.Duplicate, repository.addManualExpense(draft))
                val sameFieldsNewOperation = ManualExpenseDraft.createWithToken(
                    "125.50", "Synthetic Cafe", "2025-05-03T21:41", "Other", "manual:distinct-${UUID.randomUUID()}",
                )!!
                assertTrue(repository.addManualExpense(sameFieldsNewOperation) is AddExpenseResult.Inserted)
                val stored = withTimeout(3_000) { repository.observeExpenses().first() } as ExpenseObservation.Records
                assertEquals(2, stored.expenses.size)
                assertTrue(stored.expenses.any { it.id == expenseId })
                expenseId
            } finally {
                first.close()
            }

            val reopened = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            try {
                val repository = RoomExpenseRepository(reopened.expenses())
                val observed = withTimeout(3_000) { repository.observeExpenses().first() } as ExpenseObservation.Records
                assertEquals(2, observed.expenses.size)
                val storedExpense = observed.expenses.single { it.id == id }
                assertEquals(12_550L, storedExpense.amountPaise)
                assertEquals("Synthetic Cafe", storedExpense.merchant)
                assertEquals("2025-05-03T21:41", storedExpense.dateTime.toString())
                assertFalse(repository.deleteExpense(0))
                assertTrue(repository.deleteExpense(id))
                assertTrue(repository.deleteExpense(observed.expenses.single { it.id != id }.id))
                assertTrue((withTimeout(3_000) { repository.observeExpenses().first() } as ExpenseObservation.Records).expenses.isEmpty())
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
}
