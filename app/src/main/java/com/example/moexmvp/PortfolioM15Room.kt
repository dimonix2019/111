package com.example.moexmvp

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.time.Instant
import java.time.ZoneId

@Entity(tableName = "portfolio_m15_spread")
internal data class PortfolioM15SpreadEntity(
    @PrimaryKey val tsMillis: Long,
    val tatnClose: Double,
    val tatnpClose: Double,
    val spreadPercent: Double,
    val diff: Double
)

@Dao
internal interface PortfolioM15Dao {
    @Query("SELECT * FROM portfolio_m15_spread ORDER BY tsMillis ASC")
    suspend fun getAll(): List<PortfolioM15SpreadEntity>

    @Query("SELECT COUNT(*) FROM portfolio_m15_spread")
    suspend fun count(): Int

    @Query("SELECT MAX(tsMillis) FROM portfolio_m15_spread")
    suspend fun maxTsMillis(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PortfolioM15SpreadEntity>)

    @Query("DELETE FROM portfolio_m15_spread")
    suspend fun deleteAll()

    @Query("DELETE FROM portfolio_m15_spread WHERE tsMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}

@Database(entities = [PortfolioM15SpreadEntity::class], version = 1, exportSchema = false)
internal abstract class PortfolioM15Database : RoomDatabase() {
    abstract fun dao(): PortfolioM15Dao

    companion object {
        @Volatile
        private var instance: PortfolioM15Database? = null

        fun get(context: Context): PortfolioM15Database {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PortfolioM15Database::class.java,
                    "portfolio_m15_cache.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}

internal fun PortfolioM15SpreadEntity.toDataPoint(): DataPoint {
    val zone = ZoneId.of("Europe/Moscow")
    val ldt = Instant.ofEpochMilli(tsMillis).atZone(zone).toLocalDateTime()
    return DataPoint(
        timestampMillis = tsMillis,
        tradeDate = ldt.format(portfolio15mLabelFormatter),
        tatnClose = tatnClose,
        tatnpClose = tatnpClose,
        spreadPercent = spreadPercent,
        diff = diff,
        zScore = 0.0
    )
}
