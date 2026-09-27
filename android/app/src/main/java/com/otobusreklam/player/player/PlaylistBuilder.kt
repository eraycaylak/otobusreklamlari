package com.otobusreklam.player.player

import android.util.Log
import com.otobusreklam.player.Config
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.ContentState
import com.otobusreklam.player.data.ItemEntity
import com.otobusreklam.player.store.FileStore
import java.io.File

/**
 * Oynatma listesini kuran bilesen. Uc kurali ayni anda uygular:
 *   1. Sadece TAM INMIS ve dogrulanmis dosyalar
 *   2. Gecerlilik tarihi (cevrimdisi zorlanir - uzaktan kaldirma kanali yok)
 *   3. Saat dilimi (daypart)
 */
class PlaylistBuilder(
    private val store: FileStore,
    private val db: AppDatabase,
    private val clock: ClockManager,
    private val config: Config
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
        // Zaman ve guven TEK OKUMADA: ayri ayri sorulursa arada bir senkron gerceklesip
        // ikisi tutarsiz olabilir (bkz. ClockMath.Snapshot).
        val saat = clock.snapshot()
        val now = saat.nowMs
        val clockOk = saat.trusted

        val playable = items.filter { item ->
            val file = store.contentFile(item.sha256, item.remotePath)
            item.sha256 in readyShas && file.exists() && isEligible(item, now, clockOk)
        }

        /*
         * SON KADEME: hicbir sey uygun degilse TUM evergreen'ler devreye girer.
         *
         * Bu dal artik GERCEKTEN ulasilabilir. Onceden evergreen kosulsuz "uygun"
         * sayildigi icin (daypart yok sayiliyordu) hazir bir evergreen varsa o zaten
         * `playable` icinde oluyordu ve buraya hic dusulmuyordu: olu kod. Evergreen
         * artik daypart'a uydugu icin, "gece kusagi" tanimli bir evergreen gunduz
         * elenir - ve baska hicbir sey yoksa ekranin kararmamasi adina BURADA geri
         * gelir. Ekranin bos kalmasi her zaman en kotu sonuctur.
         */
        val sonKademe = playable.isEmpty()
        val chosen = playable.ifEmpty {
            items.filter {
                it.evergreen &&
                    it.sha256 in readyShas &&
                    store.contentFile(it.sha256, it.remotePath).exists()
            }
        }

        if (chosen.isEmpty()) {
            Log.e(TAG, "OYNATILACAK ICERIK YOK - ekran siyah kalacak")
            return Result(emptyList(), neden(items, readyShas, clockOk))
        }

        val kampanyaSayisi = playable.count { !it.evergreen }
        val reason = when {
            sonKademe && !clockOk -> "saat SUPHELI + uygun icerik yok -> tum evergreen (${chosen.size})"
            sonKademe -> "hicbiri uygun degil -> tum evergreen (${chosen.size})"
            !clockOk -> "saat SUPHELI (${saat.reason}) -> sadece evergreen (${chosen.size})"
            kampanyaSayisi > 0 -> "normal ($kampanyaSayisi kampanya + ${playable.size - kampanyaSayisi} evergreen)"
            else -> "uygun kampanya yok -> evergreen (${chosen.size})"
        }

        return Result(interleaveByWeight(chosen), reason)
    }

    /**
     * Ekran neden bos - AYRINTILI.
     *
     * "oynatilacak icerik yok" tek basina hicbir seye yaramiyordu: sebep indirilememis
     * icerik mi, oynatilamayan (BAD) kodek mi, suresi bitmis kampanyalar mi, yoksa
     * hic evergreen tanimlanmamis olmasi mi? Ucu de farkli mudahale gerektiriyor ve
     * bu metin heartbeat ile panele gidiyor.
     */
    private suspend fun neden(items: List<ItemEntity>, readyShas: Set<String>, clockOk: Boolean): String {
        val contents = db.contents().all()
        val bad = contents.count { it.state == ContentState.BAD }
        val inmeyen = items.count { it.sha256 !in readyShas }
        val evergreenVar = items.any { it.evergreen }
        return buildString {
            append("oynatilacak icerik yok")
            append(" (${items.size} oge")
            if (inmeyen > 0) append(", $inmeyen inmemis")
            if (bad > 0) append(", $bad oynatilamiyor")
            if (!evergreenVar) append(", EVERGREEN TANIMLI DEGIL")
            if (!clockOk) append(", saat supheli")
            append(")")
        }
    }

    /**
     * Gecerlilik kontrolu - kural [Eligibility] icinde, Android'den bagimsiz ve test edilmis.
     *
     * Risk asimetrik: suresi bitmis veya iptal edilmis bir reklami oynatmak sozlesmesi
     * olmayan envanter yayinlamaktir; oynatmamak ise sadece bir bosluktur. Bu yuzden
     * her belirsizlikte OYNATMIYORUZ.
     */
    private fun isEligible(item: ItemEntity, now: Long, clockTrusted: Boolean): Boolean =
        Eligibility.isEligible(
            evergreen = item.evergreen,
            clockTrusted = clockTrusted,
            validFrom = item.validFrom,
            validUntil = item.validUntil,
            dayparts = item.dayparts,
            nowMs = now,
            // Dilim SUNUCUDAN gelir. ZoneId.systemDefault() bu cihazlarda tipik olarak
            // dokunulmamis ROM varsayilanidir (sik sik UTC) ve Turkiye icin 3 saatlik
            // kayma demektir: sabah kusagi icin satilan reklam ogleden sonra doner.
            zone = config.zoneId
        )

    /**
     * Agirlikli siralama.
     *
     * weight=2 olan icerik donguye iki kez girer ama ARKA ARKAYA DEGIL:
     * her tur bir kez gecilir, boylece agirlikli icerikler donguye yayilir.
     * (agirliklar 3,1,1 ise: A B C A -> A'lar aralikli)
     */
    private fun interleaveByWeight(items: List<ItemEntity>): List<Entry> =
        Weighting.interleave(items) { it.weight }.map { item ->
            Entry(
                itemId = item.itemId,
                sha256 = item.sha256,
                file = store.contentFile(item.sha256, item.remotePath),
                durationMs = item.durationMs
            )
        }

    private companion object { const val TAG = "PlaylistBuilder" }
}
