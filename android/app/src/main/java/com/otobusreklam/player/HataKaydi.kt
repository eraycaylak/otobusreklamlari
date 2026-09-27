package com.otobusreklam.player

/**
 * SON ARIZALARIN KUCUK LISTESI - SAF, ANDROID'DEN BAGIMSIZ, TEST EDILEBILIR.
 *
 * NEDEN: `config.lastError` tek bir dizgiydi ve SEKIZ ayri yer ona yaziyordu
 * (senkron temizligi, DISK DOLU, manifest HTTP hatasi, IMZA GECERSIZ, indirici
 * hatasi, LOG TASMASI, oge indirme hatasi, EKRAN BOS). Son yazan kazaniyor, digerleri
 * KAYBOLUYORDU.
 *
 * Somut zarar: disk dolar (yazilir), bir ogenin indirmesi hata verir (uzerine yazar),
 * log tasmasi olur (uzerine yazar). Panelde yalnizca "LOG TASMASI: 1200 kayit silindi"
 * gorunur. Operator ASIL SEBEBI (disk dolu) hic gormez, log tasmasini kovalar ve
 * fatura kaybinin kokunu bulamaz. `lastInstallError` icin bu problem zaten kabul
 * edilip ayri alan acilmisti; ayni cikarim digerlerine uygulanmamisti.
 *
 * Tasarim kararlari:
 *  - KOD BASINA TEK KAYIT: tekrarlayan "disk dolu" diger arizalari listeden atmasin.
 *    Ayni kod yeniden gelirse metin ve zaman guncellenir, yeri korunur.
 *  - SINIRLI: en fazla [MAX] kayit. Heartbeat govdesi sisemez (sunucu 256 KB'da kesiyor
 *    ve her heartbeat IKI yere kaliciya yaziliyor).
 *  - MONOTONIK ZAMAN: bu cihazlarda duvar saati guvenilmez. Yas, elapsedRealtime
 *    farkindan hesaplaniyor; cihaz 1970'te olsa bile "2 dakika once" dogru kalir.
 *  - Depolama bicimi tek satirlik metin: SharedPreferences'ta JSON ayristirici
 *    gerektirmeden saklanabilsin (kaydin kendisi ariza aninda yazilacak, en az
 *    hareketli parca en iyisidir).
 */
object HataKaydi {

    const val MAX = 6

    /** Tek bir ariza notu. [uptimeMs] = yazildigi andaki SystemClock.elapsedRealtime. */
    data class Not(val kod: String, val metin: String, val uptimeMs: Long)

    private const val ALAN = '\u001f'   // birim ayirici: serbest metinde gecmez
    private const val SATIR = '\u001e'  // kayit ayirici
    private const val METIN_SINIRI = 200

    /**
     * Yeni bir not ekle (ya da ayni kodu guncelle) ve siniri uygula.
     * En yeni kayit BASTA olur: panel ilk satirlari gosteriyor.
     */
    fun ekle(mevcut: List<Not>, kod: String, metin: String, uptimeMs: Long): List<Not> {
        val temizKod = kod.trim().ifBlank { "genel" }.take(24)
        val yeni = Not(temizKod, metin.trim().take(METIN_SINIRI), uptimeMs)
        val kalan = mevcut.filter { it.kod != temizKod }
        return (listOf(yeni) + kalan).take(MAX)
    }

    /** Kodu verilen notu kaldir (ariza duzeldi). */
    fun sil(mevcut: List<Not>, kod: String): List<Not> = mevcut.filter { it.kod != kod }

    fun kodla(notlar: List<Not>): String =
        notlar.take(MAX).joinToString(SATIR.toString()) { n ->
            // Ayiricilar metinden temizlenir: aksi halde tek bir kayit listeyi bozar.
            val m = n.metin.replace(ALAN, ' ').replace(SATIR, ' ')
            "${n.kod}$ALAN$m$ALAN${n.uptimeMs}"
        }

    fun coz(ham: String?): List<Not> {
        if (ham.isNullOrBlank()) return emptyList()
        return ham.split(SATIR).mapNotNull { satir ->
            val p = satir.split(ALAN)
            if (p.size < 3) return@mapNotNull null
            val ms = p[2].toLongOrNull() ?: return@mapNotNull null
            Not(p[0], p[1], ms)
        }.take(MAX)
    }

    /**
     * Heartbeat icin: en yeniden eskiye, yasi SANIYE cinsinden.
     *
     * Cihaz yeniden baslarsa elapsedRealtime sifirlanir ve eski notlarin yasi NEGATIF
     * cikar. O durumda yas bilinmiyor demektir (-1): yanlis bir sayi gostermek,
     * hicbir sey gostermemekten kotudur.
     */
    fun yasSaniye(not: Not, simdiUptimeMs: Long): Long {
        val fark = simdiUptimeMs - not.uptimeMs
        return if (fark < 0) -1 else fark / 1000
    }
}
