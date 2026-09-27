package com.otobusreklam.player.player

import com.otobusreklam.player.Config
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.PlayLogEntity
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Oynatma kaniti (proof-of-play) kaydi.
 * Reklamverene kesilen faturanin dayanagi bu satirlar.
 *
 * clockTrusted alani bilerek tasiniyor: saati supheli cihazdan gelen kayit
 * raporda ayri sutunda gosterilir, boylece denetimde tartisma cikmaz.
 */
class PlaybackLogger(
    private val db: AppDatabase,
    private val clock: ClockManager,
    private val config: Config
) {

    /**
     * Kendi kapsamindan yaziyoruz, cagiranin kapsamindan DEGIL.
     *
     * Onceden kayitlar aktivitenin lifecycleScope'undan yaziliyordu. Aktivite
     * kapanirken (onDestroy) son oynatmayi kaydetmeye calisiyorduk ama o anda
     * lifecycleScope ZATEN IPTAL EDILMIS oluyordu: coroutine hic calismiyor ve
     * son oynatma kaydi sessizce kayboluyordu. Faturanin dayanagi olan bir veri
     * icin kabul edilemez.
     */
    /*
     * ISTISNA ISLEYICISI ZORUNLU.
     *
     * SupervisorJob kardes coroutine'leri iptal etmekten korur ama YAKALANMAMIS
     * istisnayi yutmaz: istisna surecin varsayilan isleyicisine gider ve KIOSK
     * UYGULAMASINI COKERTIR. Disk dolmasi veya kilitli bir veritabani yuzunden
     * ekranin kararmasi kabul edilemez - bir log satirini kaybetmek cok daha ucuz.
     */
    private val isleyici = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "oynatma kaydi yazilamadi (yok sayiliyor, ekran calismaya devam ediyor)", e)
    }

    private val kapsam = CoroutineScope(SupervisorJob() + Dispatchers.IO + isleyici)

    /**
     * Kapanis/yeniden baslatma oncesi SON kaydi diske yazmayi bekler.
     *
     * Normal kaydet() atesle-ve-unuttur; cihaz hemen yeniden baslatilacaksa kuyruktaki
     * yazma hic diske inmez ve o oynatma faturaya girmez. Kisa bir zaman asimiyla
     * bekliyoruz: kayit onemli ama kapanisi kilitlemesine de degmez.
     */
    fun bekleyerekKaydet(
        itemId: String,
        sha256: String,
        startedAt: Long,
        durationMs: Long,
        completed: Boolean,
        clockTrusted: Boolean,
        playlistVersion: Int
    ) {
        runCatching {
            runBlocking {
                withTimeoutOrNull(2_000) {
                    record(itemId, sha256, startedAt, durationMs, completed, clockTrusted, playlistVersion)
                }
            }
        }.onFailure { Log.e(TAG, "son kayit yazilamadi", it) }
    }

    /** Atesle-ve-unut: cagiranin yasam dongusune bagli degildir. */
    fun kaydet(
        itemId: String,
        sha256: String,
        startedAt: Long,
        durationMs: Long,
        completed: Boolean,
        clockTrusted: Boolean,
        playlistVersion: Int
    ) {
        kapsam.launch { record(itemId, sha256, startedAt, durationMs, completed, clockTrusted, playlistVersion) }
    }

    /**
     * @param clockTrusted OYNATMANIN BASLADIGI andaki saat guveni
     * @param playlistVersion oynatmanin basladigi andaki liste surumu
     *
     * Bu ikisi neden disaridan geliyor: onceden YAZMA aninda okunuyorlardi. Yazma
     * asenkron oldugu icin, arada bir senkron gerceklesip saati guvenilir yaparsa
     * 1970 damgali bir kayit "saati guvenilir" olarak faturaya girebiliyordu.
     */
    suspend fun record(
        itemId: String,
        sha256: String,
        startedAt: Long,
        durationMs: Long,
        completed: Boolean,
        clockTrusted: Boolean,
        playlistVersion: Int
    ) {
        db.playLog().insert(
            PlayLogEntity(
                itemId = itemId,
                sha256 = sha256,
                startedAt = startedAt,
                durationMs = durationMs,
                completed = completed,
                playlistVersion = playlistVersion,
                clockTrusted = clockTrusted
            )
        )
    }

    private companion object { const val TAG = "PlaybackLogger" }
}
