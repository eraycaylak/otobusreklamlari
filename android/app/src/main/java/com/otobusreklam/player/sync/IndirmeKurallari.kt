package com.otobusreklam.player.sync

import com.otobusreklam.player.manifest.ChunkSpec

/**
 * INDIRMENIN KARAR KURALLARI - SAF, ANDROID'DEN BAGIMSIZ, TEST EDILEBILIR.
 *
 * Projenin cekirdek ozelligi devam ettirilebilir parcali indirme, ama bu kararlar
 * Room + OkHttp + RandomAccessFile'a bagli bir suspend fonksiyonun ICINE gomuluydu:
 * yani hicbiri birim testiyle kilitlenemiyordu. Oysa bu uc kural dustugunde sonuc
 * ya SONSUZ yeniden indirme, ya YAKALANAMAYAN bir OutOfMemoryError, ya da SESSIZCE
 * bozuk bir dosyadir - ucu de 3 dakikalik pencereyi kalici olarak yok eder.
 *
 * Kurallar buraya cikarildi; Downloader onlari cagiriyor.
 * Donus sozlesmesi: null = KABUL, metin = RED SEBEBI (panelde gorunecek mesaj).
 */
object ParcaDogrulama {

    /**
     * Tek parca icin kabul edilen en buyuk boyut.
     * Parca tamamen bellege alindigi icin bu dogrudan bir BELLEK sinirdir.
     * Sunucu 4 MB parca uretiyor; 16 MB bol pay birakir.
     */
    const val MAX_PARCA = 16 * 1024 * 1024

    /**
     * Manifestteki parca listesi dosyayi TAM kapsiyor ve parcalar MAKUL boyutta mi?
     *
     * Kapsamiyorsa (sunucu hatasi, kirpilmis manifest) tum parcalar "indi" olur, tam
     * dosya hash'i TUTMAZ, dosya bastan indirilir - ve bu SONSUZA KADAR tekrarlanir.
     * Her pencerede tum bant genisligi ayni dosyaya harcanir.
     *
     * Ust sinir da ZORUNLU: manifestteki bir `len` 500 MB olsa ByteArray(len)
     * OutOfMemoryError firlatir - ve OOM bir Error'dur, indirme dongusundeki
     * `catch (e: Exception)` bloklarinin HICBIRI onu tutmaz: kiosk uygulamasi ekrani
     * karartarak coker.
     */
    fun kontrol(chunks: List<ChunkSpec>, size: Long): String? {
        if (chunks.isEmpty()) return "manifest parca listesi bos (beklenen $size bayt)"
        val bozuk = chunks.firstOrNull { it.len <= 0 || it.len > MAX_PARCA }
        if (bozuk != null) {
            return "manifest parca boyutu kabul edilemez: idx=${bozuk.index} len=${bozuk.len} (sinir $MAX_PARCA)"
        }
        val kapsam = chunks.sumOf { it.len.toLong() }
        if (kapsam != size) {
            return "manifest parca listesi bozuk: ${chunks.size} parca $kapsam bayt, beklenen $size"
        }
        return null
    }
}

object YanitKabul {

    /**
     * Sunucunun yaniti GERCEKTEN istedigimiz aralik mi?
     *
     * Uc ayri tuzak var ve ucu de SESSIZCE bozuk dosya uretir:
     *
     *  1. HTTP 200: sunucu/proxy Range'i YOK SAYMIS demektir. O govdeyi parcanin
     *     offset'ine yazmak dosyayi bozar. Tek istisna: dosya tek parcadan olusuyor ve
     *     offset 0 - o durumda 200 govdesi zaten TAM dosyadir.
     *  2. YANLIS ARALIK: `Range`'i kismen destekleyen bir proxy ya da arada duran bir
     *     onbellek baska bir araligi dondurebilir. Onceden bu ancak parca hash'inde
     *     anlasiliyor ve teshis edilemez bir "parca hash tutmadi" satiri olarak
     *     goruluyordu.
     *  3. KISA/UZUN GOVDE: uzunluk bilinip farkliysa parcayi hic okumaya gerek yok.
     *
     * @param contentRange sunucunun Content-Range basligi (bos/null olabilir)
     * @param contentLength govde uzunlugu; bilinmiyorsa -1
     */
    fun kabulEdilirMi(
        code: Int,
        contentRange: String?,
        contentLength: Long,
        offset: Long,
        len: Int,
        totalChunks: Int,
        url: String = ""
    ): String? {
        val tekParcaTamDosya = totalChunks == 1 && offset == 0L
        if (code != 206 && !(code == 200 && tekParcaTamDosya)) {
            return when (code) {
                200 -> "sunucu Range desteklemiyor (200 dondu): $url"
                // Onbellek kutusu Authorization basligini yukari gecirmiyor olabilir.
                401, 403 -> "icerik indirme yetkisi reddedildi (HTTP $code) - onbellek kutusu Authorization basligini geciriyor mu?"
                404 -> "icerik sunucuda yok (404): $url"
                else -> "icerik indirme HTTP $code: $url"
            }
        }
        if (code == 206) {
            val cr = contentRange.orEmpty()
            val beklenen = "bytes $offset-${offset + len - 1}/"
            if (cr.isNotBlank() && !cr.startsWith(beklenen)) {
                return "sunucu yanlis aralik dondu: istenen $beklenen, gelen $cr"
            }
        }
        if (contentLength >= 0 && contentLength != len.toLong()) {
            return "govde uzunlugu beklenenden farkli: $contentLength != $len ($url)"
        }
        return null
    }
}

object SifirlamaKarari {

    /**
     * Veritabani "tum parcalar indi" diyor ama yarim dosya beklenen boyutta DEGIL.
     *
     * Diski temizleyen biri, bozuk bir kapanma ya da dolu diskte kirpilan bir yazma
     * bu duruma yol acar. Sifirlamazsak dosya sessizce SIFIRLARLA dolu kalir ve hata
     * ancak tam dosya hash'inde ortaya cikar - yani tum dosya bir kez daha inmek
     * zorunda kalir.
     *
     * DIKKAT: bu kontrol bir zamanlar OLU KODDU. RandomAccessFile.setLength(size)
     * cagrisindan SONRA bakiliyordu ve o noktada uzunluk HER ZAMAN size'a esittir.
     * Bu yuzden karar, dosya uzunlugunun RAF ACILMADAN ONCE okunmus haliyle verilir.
     */
    fun sifirlaMi(bekleyenParcaVarMi: Boolean, oncekiUzunluk: Long, size: Long): Boolean =
        !bekleyenParcaVarMi && oncekiUzunluk != size
}
