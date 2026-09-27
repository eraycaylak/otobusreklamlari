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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(content: ContentEntity): Long

    @Query("UPDATE contents SET remotePath = :remotePath, size = :size, state = :state, updatedAt = :now WHERE sha256 = :sha")
    suspend fun updateFields(sha: String, remotePath: String, size: Long, state: String, now: Long)

    /**
     * failCount'u KORUYAN upsert.
     *
     * Onceden bu @Insert(REPLACE) idi. REPLACE bir SIL+EKLE'dir: satirin tum alanlari,
     * dolayisiyla failCount da SIFIRLANIR. Oysa failCount "bu dosya kac kez
     * oynatilamadi" sayacidir ve esigi (3) astiginda icerik BAD isaretlenir.
     * Her senkron sayaci sifirladigi icin ARALIKLI kodek hatalari o esige HIC
     * ulasamiyordu: bozuk icerik sonsuza kadar donguye girip her turda ekrani
     * bir sure karartiyor, panelde ise hicbir sey gorunmuyordu.
     */
    @Transaction
    suspend fun upsert(content: ContentEntity) {
        if (insertIfAbsent(content) == -1L) {
            updateFields(content.sha256, content.remotePath, content.size, content.state, content.updatedAt)
        }
    }

    @Query("UPDATE contents SET state = :state, updatedAt = :now WHERE sha256 = :sha")
    suspend fun setState(sha: String, state: String, now: Long)

    @Query("UPDATE contents SET failCount = failCount + 1 WHERE sha256 = :sha")
    suspend fun bumpFail(sha: String)

    /** Yeni bir kodlama indi (sha degisti): sayac bilincli olarak sifirlanir. */
    @Query("UPDATE contents SET failCount = 0 WHERE sha256 = :sha")
    suspend fun clearFail(sha: String)

    @Query("DELETE FROM contents WHERE sha256 = :sha")
    suspend fun delete(sha: String)

    /** Manifestten dusen icerigin satirini birak. Bkz. [AppDatabase] temizlik notu. */
    @Query("DELETE FROM contents WHERE sha256 NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<String>)

    @Query("DELETE FROM contents")
    suspend fun deleteAll()
}

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE sha256 = :sha ORDER BY idx")
    suspend fun forContent(sha: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE sha256 = :sha AND done = 0 ORDER BY idx")
    suspend fun pending(sha: String): List<ChunkEntity>

    @Query("SELECT COALESCE(SUM(len), 0) FROM chunks WHERE sha256 = :sha AND done = 0")
    suspend fun remainingBytes(sha: String): Long

    /**
     * Bu icerik icin HIC parca kaydi var mi?
     *
     * remainingBytes() tek basina iki TAMAMEN FARKLI durumu ayirt edemiyor:
     *   (a) hic baslanmadi  -> satir yok        -> SUM = 0
     *   (b) tamami indi     -> hepsi done=1     -> SUM = 0
     * Cagiran 0'i "hic baslanmadi" sayip tum dosya boyutunu kalan olarak bildirince,
     * %100 inmis ama henuz READY isaretlenmemis bir dosya indirme siralamasinda EN
     * SONA atiliyordu - yani 3 dakikalik pencerede once kocaman bir dosya cekiliyor,
     * bitmeye 1 parca kalmis olan beklemede kaliyordu.
     */
    @Query("SELECT COUNT(*) FROM chunks WHERE sha256 = :sha")
    suspend fun count(sha: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("UPDATE chunks SET done = 1 WHERE sha256 = :sha AND idx = :idx")
    suspend fun markDone(sha: String, idx: Int)

    @Query("DELETE FROM chunks WHERE sha256 = :sha")
    suspend fun clear(sha: String)

    /** Artik manifestte olmayan iceriklerin parca kayitlarini birak. */
    @Query("DELETE FROM chunks WHERE sha256 NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<String>)

    @Query("DELETE FROM chunks")
    suspend fun deleteAll()

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

    /**
     * Yuklendi isaretle - PARTININ USTUNE TASMADAN.
     *
     * ackSeq sunucudan gelen, IMZASIZ bir HTTP govdesindeki sayidir. Sinirsiz
     * uygulandiginda tek bir buyuk deger (sunucu hatasi, bozuk onbellek yaniti veya
     * kotu niyet) cihazdaki TUM bekleyen oynatma kayitlarini "yuklendi" yapardi;
     * temizlik turu da onlari sildigi icin o otobusun faturasi geri donusu olmayan
     * bicimde kaybolurdu. Artik yalnizca GERCEKTEN GONDERDIGIMIZ partinin en buyuk
     * seq'ine kadar isaretliyoruz.
     */
    @Query("UPDATE play_log SET uploaded = 1 WHERE uploaded = 0 AND seq <= :ackSeq AND seq <= :batchMaxSeq")
    suspend fun markUploaded(ackSeq: Long, batchMaxSeq: Long)

    /** Yuklenmis ve verilen tarihten eski kayitlari temizle - disk sismesin. */
    @Query("DELETE FROM play_log WHERE uploaded = 1 AND startedAt < :before")
    suspend fun purgeOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM play_log WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM play_log")
    suspend fun totalCount(): Int

    /**
     * YUKLENEMEYEN kayitlar icin son care tavani.
     *
     * Onceden temizlik yalnizca uploaded=1 satirlarini siliyordu, yani sunucuya hic
     * ulasamayan bir cihazda play_log SINIRSIZ buyuyordu. Bu cihazlarda disk 8-16 GB:
     * dolu disk once indirmeyi, sonra veritabani yazmayi, yani ekrani durdurur.
     * Fatura verisini silmek istemiyoruz ama ekranin kararmasindan da iyidir; bu
     * yuzden tavan cok yukari (bkz. AppDatabase.LOG_TAVANI) ve silme EN ESKIDEN
     * baslar, ustelik loglanir.
     */
    @Query("DELETE FROM play_log WHERE seq IN (SELECT seq FROM play_log ORDER BY seq LIMIT :howMany)")
    suspend fun dropOldest(howMany: Int)

    /**
     * Kanit karesi icin: en son oynatilan icerik - ANCAK VERILEN ANDAN SONRAYSA.
     *
     * Zaman siniri olmadan, ekran gunlerdir bos olsa bile (icerik BAD, disk dolu,
     * liste bos) en son kayit bulunup uzerine GUNCEL zaman damgasi basiliyor ve
     * reklamverene "bu icerik su an oynuyor" izlenimi veren bir kanit uretiliyordu.
     * Kanitin yanlis olmasi, kanit olmamasindan kotudur.
     */
    @Query("SELECT * FROM play_log WHERE startedAt >= :since ORDER BY seq DESC LIMIT 1")
    suspend fun lastPlayed(since: Long): PlayLogEntity?
}
