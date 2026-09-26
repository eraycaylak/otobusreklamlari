package com.otobusreklam.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ItemDao {
    @Query("SELECT * FROM items")
    suspend fun all(): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE itemId NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<String>)

    @Query("DELETE FROM items")
    suspend fun deleteAll()
}

@Dao
interface ContentDao {
    @Query("SELECT * FROM contents WHERE sha256 = :sha")
    suspend fun get(sha: String): ContentEntity?

    @Query("SELECT * FROM contents")
    suspend fun all(): List<ContentEntity>

    @Query("SELECT sha256 FROM contents WHERE state = :state")
    suspend fun shasWithState(state: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(content: ContentEntity)

    @Query("UPDATE contents SET state = :state, updatedAt = :now WHERE sha256 = :sha")
    suspend fun setState(sha: String, state: String, now: Long)

    @Query("UPDATE contents SET failCount = failCount + 1 WHERE sha256 = :sha")
    suspend fun bumpFail(sha: String)

    @Query("DELETE FROM contents WHERE sha256 = :sha")
    suspend fun delete(sha: String)
}

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE sha256 = :sha ORDER BY idx")
    suspend fun forContent(sha: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE sha256 = :sha AND done = 0 ORDER BY idx")
    suspend fun pending(sha: String): List<ChunkEntity>

    @Query("SELECT COALESCE(SUM(len), 0) FROM chunks WHERE sha256 = :sha AND done = 0")
    suspend fun remainingBytes(sha: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("UPDATE chunks SET done = 1 WHERE sha256 = :sha AND idx = :idx")
    suspend fun markDone(sha: String, idx: Int)

    @Query("DELETE FROM chunks WHERE sha256 = :sha")
    suspend fun clear(sha: String)

    @Transaction
    suspend fun reset(sha: String, chunks: List<ChunkEntity>) {
        clear(sha)
        insertAll(chunks)
    }
}

@Dao
interface PlayLogDao {
    @Insert
    suspend fun insert(row: PlayLogEntity): Long

    @Query("SELECT * FROM play_log WHERE uploaded = 0 ORDER BY seq LIMIT :limit")
    suspend fun pending(limit: Int): List<PlayLogEntity>

    @Query("UPDATE play_log SET uploaded = 1 WHERE seq <= :ackSeq")
    suspend fun markUploaded(ackSeq: Long)

    /** Yuklenmis ve 30 gunden eski kayitlari temizle - disk sismesin. */
    @Query("DELETE FROM play_log WHERE uploaded = 1 AND startedAt < :before")
    suspend fun purgeOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM play_log WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    /** Kanit karesi icin: en son oynatilan icerik. */
    @Query("SELECT * FROM play_log ORDER BY seq DESC LIMIT 1")
    suspend fun lastPlayed(): PlayLogEntity?
}
