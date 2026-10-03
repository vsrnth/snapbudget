package dev.snapbudget

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "expenses", indices = [Index(value = ["transactionId"], unique = true), Index(value = ["imageHash"], unique = true)])
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val merchant: String,
    // Local receipt time: no guessed timezone conversion.
    val dateTime: String,
    val category: String,
    val transactionId: String?,
    val imageHash: String,
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY dateTime DESC, id DESC")
    fun observe(): Flow<List<Expense>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long
    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [Expense::class], version = 1, exportSchema = false)
abstract class ExpenseDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao
    companion object {
        @Volatile private var instance: ExpenseDatabase? = null
        fun get(context: Context): ExpenseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ExpenseDatabase::class.java, "expenses.db")
                .build().also { instance = it }
        }
    }
}
