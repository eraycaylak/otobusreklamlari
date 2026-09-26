package com.otobusreklam.player.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Icerik dosyasinin indirme durumu. */
object ContentState {
    const val MISSING = "MISSING"
    const val PARTIAL = "PARTIAL"
    const val READY = "READY"
    /** Oynatilamadi (bozuk kodek/dosya). Donguden cikarilir, sonraki senkronda yeniden denenir. */
    const val BAD = "BAD"
}

/**
 * Yayin listesi ogesi (kampanya).
 * Birden fazla kampanya AYNI dosyayi gosterebilir; bu yuzden dosya ayri tabloda.
 */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val itemId: String,
    val sha256: String,
    val remotePath: String,
    val durationMs: Long,
    val weight: Int,
    val validFrom: Long?,
    val validUntil: Long?,
    /** "07:00-10:00,17:00-20:00" - bos ise gun boyu */
    val dayparts: String,
    val evergreen: Boolean,
    val playlistVersion: Int
)

/** Fiziksel dosya. Icerik adresli: ayni sha bir kez iner. */
@Entity(tableName = "contents")
data class ContentEntity(
    @PrimaryKey val sha256: String,
    val remotePath: String,
    val size: Long,
    val state: String,
    val updatedAt: Long,
    /** Kac kez oynatma hatasi verdi - esik asilinca BAD isaretlenir. */
    val failCount: Int = 0
)

/**
 * Parca durumu. Senkronun kalbi burasi:
 * pencere bittiginde hangi parcalarin indigi BURADA kalir, yarim dosya silinmez.
 */
@Entity(
    tableName = "chunks",
    primaryKeys = ["sha256", "idx"],
    indices = [Index(value = ["sha256", "done"])]
)
data class ChunkEntity(
    val sha256: String,
    val idx: Int,
    val offset: Long,
    val len: Int,
    val chunkSha: String,
    val done: Boolean
)

/**
 * Oynatma kaydi. seq OTOMATIK ARTAR ve kalicidir; sunucu tekrarlari (device, seq)
 * ile eler. ACK gelmeden satir SILINMEZ.
 */
@Entity(tableName = "play_log", indices = [Index("uploaded")])
data class PlayLogEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val itemId: String,
    val sha256: String,
    val startedAt: Long,
    val durationMs: Long,
    val completed: Boolean,
    val playlistVersion: Int,
    val clockTrusted: Boolean,
    val uploaded: Boolean = false
)
