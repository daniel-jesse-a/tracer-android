package app.tracer.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room DAO for the only Room-managed table, `runs`. Sample tables are accessed with raw SQL. */
@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: RunEntity)

    @Update
    suspend fun update(run: RunEntity): Int

    @Query("SELECT * FROM runs WHERE run_id = :runId")
    suspend fun get(runId: String): RunEntity?

    @Query("SELECT * FROM runs ORDER BY started_at DESC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query(
        "SELECT * FROM runs WHERE ended_at IS NULL AND device_platform = 'android' " +
            "ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun unfinished(): RunEntity?
}
