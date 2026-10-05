package dev.snapbudget

import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class ExpenseDatabaseTest {
    @Test fun persistenceDeduplicationIsolationAndCleanupAreLocal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage-${UUID.randomUUID()}.db"
        try {
            val first = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            val firstId = try {
                runBlocking {
                    val dao = first.expenses()
                    val original = dao.insert(expense(transactionId = "T111111111111111111", imageHash = "hash-a"))
                    assertTrue(original > 0)
                    assertEquals(-1L, dao.insert(expense(transactionId = "T111111111111111111", imageHash = "hash-b")))
                    assertEquals(-1L, dao.insert(expense(transactionId = "T222222222222222222", imageHash = "hash-a")))
                    assertEquals(1, withTimeout(3_000) { dao.observe().first() }.size)
                    original
                }
            } finally {
                first.close()
            }

            val reopened = Room.databaseBuilder(context, ExpenseDatabase::class.java, name).build()
            try {
                runBlocking {
                    val rows = withTimeout(3_000) { reopened.expenses().observe().first() }
                    assertEquals(1, rows.size)
                    assertEquals(firstId, rows.single().id)
                    assertEquals("Synthetic Cafe", rows.single().merchant)
                    assertEquals(12_550L, rows.single().amountPaise)
                    assertEquals("2025-05-03T21:41", rows.single().dateTime)
                }
            } finally {
                reopened.close()
            }

            val isolatedName = "isolation-${UUID.randomUUID()}.db"
            try {
                val isolated = Room.databaseBuilder(context, ExpenseDatabase::class.java, isolatedName).build()
                try {
                    assertTrue(runBlocking { withTimeout(3_000) { isolated.expenses().observe().first() } }.isEmpty())
                } finally {
                    isolated.close()
                }
            } finally {
                context.deleteDatabase(isolatedName)
            }
            assertTrue(context.getDatabasePath(name).parentFile!!.canonicalPath.startsWith(context.dataDir.canonicalPath))
        } finally {
            context.deleteDatabase(name)
            assertFalse(context.getDatabasePath(name).exists())
            assertFalse(context.getDatabasePath("$name-wal").exists())
            assertFalse(context.getDatabasePath("$name-shm").exists())
        }
    }

    @Test fun packagedManifestHasNoInternetPermissionAndDisablesBackup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        fun permissions(packageName: String) = context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        val forbiddenPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
        assertTrue(permissions(context.packageName).intersect(forbiddenPermissions).isEmpty())
        assertTrue(permissions(instrumentation.context.packageName).intersect(forbiddenPermissions).isEmpty())
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    private fun expense(transactionId: String, imageHash: String) = Expense(
        amountPaise = 12_550L,
        merchant = "Synthetic Cafe",
        dateTime = "2025-05-03T21:41",
        category = "Other",
        transactionId = transactionId,
        imageHash = imageHash,
    )
}
