package com.otobusreklam.player.player

import android.util.Log
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.ContentState
import com.otobusreklam.player.data.ItemEntity
import com.otobusreklam.player.store.FileStore
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Oynatma listesini kuran bilesen. Uc kurali ayni anda uygular:
 *   1. Sadece TAM INMIS ve dogrulanmis dosyalar
 *   2. Gecerlilik tarihi (cevrimdisi zorlanir - uzaktan kaldirma kanali yok)
 *   3. Saat dilimi (daypart)
 */
class PlaylistBuilder(
    private val store: FileStore,
    private val db: AppDatabase,
    private val clock: ClockManager
) {

    data class Entry(
        val itemId: String,
        val sha256: String,
        val file: File,
        val durationMs: Long
    )

    /** Liste neden boyle olustu - teshis ekraninda gosterilir. */
    data class Result(
        val entries: List<Entry>,
        val reason: String
    )

    suspend fun build(): Result {
        val items = db.items().all()
        if (items.isEmpty()) return Result(emptyList(), "liste bos")

        val readyShas = db.contents().shasWithState(ContentState.READY).toSet()
        val now = clock.now()
        val clockOk = clock.trusted()

        val playable = items.filter { item ->
            val file = store.contentFile(item.sha256, item.remotePath)
            item.sha256 in readyShas && file.exists() && isEligible(item, now, clockOk)
        }

        // Hicbir kampanya uygun degilse EVERGREEN'e dus.
        // Ekranin siyah kalmasi her zaman en kotu sonuctur.
        val chosen = playable.ifEmpty {
            items.filter {
                it.evergreen &&
                    it.sha256 in readyShas &&
                    store.contentFile(it.sha256, it.remotePath).exists()
            }
        }

        if (chosen.isEmpty()) {
            Log.e(TAG, "OYNATILACAK ICERIK YOK - ekran siyah kalacak")
            return Result(emptyList(), "oynatilacak icerik yok")
        }

        val reason = when {
            playable.isNotEmpty() -> "normal (${playable.size} icerik)"
            !clockOk -> "saat supheli -> sadece evergreen"
            else -> "uygun kampanya yok -> evergreen"
        }

        return Result(interleaveByWeight(chosen), reason)
    }

    /**
     * Gecerlilik kontrolu.
     *
     * SAAT SUPHELIYSE kampanya icerigi OYNATILMAZ.
     * Sebep ticari: suresi bitmis veya iptal edilmis bir reklami yayinlamak,
     * sozlesmesi olmayan envanter satmak demektir. 4G olmadigi icin uzaktan
     * "kaldir" komutu yok; tek guvencemiz cihazin kendi kararIdir.
     * Supheliyken oynatmamak, supheliyken oynatmaktan her zaman daha ucuzdur.
     */
    private fun isEligible(item: ItemEntity, now: Long, clockTrusted: Boolean): Boolean {
        if (item.evergreen) return true
        if (!clockTrusted) return false
        if (item.validFrom != null && now < item.validFrom) return false
        if (item.validUntil != null && now > item.validUntil) return false
        return inDaypart(item.dayparts, now)
    }

    private fun inDaypart(spec: String, now: Long): Boolean {
        if (spec.isBlank()) return true
        val localNow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()

        return spec.split(",").filter { it.isNotBlank() }.any { range ->
            val parts = range.trim().split("-")
            if (parts.size != 2) return@any true
            try {
                val start = LocalTime.parse(parts[0].trim())
                val end = LocalTime.parse(parts[1].trim())
                // Gece yarisini asan araliklar: 22:00-02:00
                if (end.isAfter(start)) {
                    !localNow.isBefore(start) && localNow.isBefore(end)
                } else {
                    !localNow.isBefore(start) || localNow.isBefore(end)
                }
            } catch (_: Exception) {
                true  // bozuk tanim yuzunden reklami dusurmeyelim
            }
        }
    }

    /**
     * Agirlikli siralama.
     *
     * weight=2 olan icerik donguye iki kez girer ama ARKA ARKAYA DEGIL:
     * her tur bir kez gecilir, boylece agirlikli icerikler donguye yayilir.
     * (agirliklar 3,1,1 ise: A B C A -> A'lar aralikli)
     */
    private fun interleaveByWeight(items: List<ItemEntity>): List<Entry> {
        val maxWeight = items.maxOf { it.weight }
        val out = ArrayList<Entry>()
        for (pass in 1..maxWeight) {
            for (item in items) {
                if (item.weight >= pass) {
                    out += Entry(
                        itemId = item.itemId,
                        sha256 = item.sha256,
                        file = store.contentFile(item.sha256, item.remotePath),
                        durationMs = item.durationMs
                    )
                }
            }
        }
        return out
    }

    private companion object { const val TAG = "PlaylistBuilder" }
}
