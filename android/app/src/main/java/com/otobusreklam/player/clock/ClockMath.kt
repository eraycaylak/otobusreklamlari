package com.otobusreklam.player.clock

/**
 * Saat hesaplari. Android'e bagimli DEGIL -> birim testi yazilabilir.
 *
 * Fikir: sistem saatine guvenmiyoruz (ucuz stick'lerde pil destekli RTC yok,
 * guc kesilince sifirlanir). Onun yerine sunucudan alinan gercek zamani bir
 * "capa" olarak saklayip, uzerine MONOTONIK sayaci (elapsedRealtime) ekliyoruz.
 *
 * Monotonik sayac cihaz yeniden baslayinca sifirlanir; bu durumda capa gecersizdir.
 *
 * YENIDEN BASLATMAYI NASIL TESPIT EDIYORUZ:
 * Eskiden yalnizca "elapsedNow < anchor.elapsedMs" kontrolu vardi ve bu TEK YONLU
 * bir testtir - dolayisiyla sessizce yanilabilir:
 *   capa acilistan 2 dk sonra alindi (elapsed=2dk), cihaz 5 saat calisti, yeniden
 *   basladi, 3 dakika sonra bakiyoruz: elapsedNow=3dk > 2dk, yani "yeniden baslamamis"
 *   deniyor. Capa 5 saat GERIDE kalmis bir zamani gosteriyor ve GUVENILIR sayiliyor.
 * Cozum: capaya cihazin ACILIS SAYACI (Settings.Global.BOOT_COUNT) yaziliyor. Bu
 * sayac sistem tarafindan her acilista bir artar. Sayac degistiyse cihaz yeniden
 * baslamistir - yon, tolerans, tahmin yok. Sayac okunamayan cihazlarda (eski ROM,
 * SecurityException) eski tek yonlu sezgiye dusuluyor.
 */
object ClockMath {

    /** Acilis sayaci okunamadi. */
    const val BOOT_UNKNOWN = -1

    /**
     * Capanin KABUL EDILEBILIR EN BUYUK YASI.
     *
     * Neden var: monotonik sayac da kusursuz degil (ucuz SoC'lerde sicaklikla
     * kayan osilatorler) ve daha onemlisi, haftalarca merkezle hic konusmamis bir
     * cihazin "saatim kesin dogru" demesi ticari olarak yanlis: o cihaz IPTAL
     * EDILMIS bir reklami hala oynatiyor olabilir. Yas asildiginda saat SUPHELI
     * olur; sonuc, bitis tarihli iceriklerin durup evergreen'e dusulmesidir - yani
     * guvenli taraf.
     */
    const val MAX_ANCHOR_AGE_MS = 30L * 24 * 3600 * 1000

    /**
     * Sunucudan alinan gercek zaman + o andaki monotonik sayac degeri.
     *
     * @param bootCount capanin alindigi andaki Settings.Global.BOOT_COUNT
     * @param signed capa IMZALI manifest govdesinden mi geldi?
     *   HTTP `Date` basligi imzanin DISINDADIR: aga erisebilen biri onu degistirebilir.
     *   Bu yuzden imzasiz kaynak yalnizca "1970 damgasi atmaktan iyidir" seviyesinde
     *   kullanilir (ZAYIF capa): zamani duzeltir ama GUVENILIR yapmaz, dolayisiyla
     *   bitis tarihi zorlamasini etkilemez.
     */
    data class Anchor(
        val epochMs: Long,
        val elapsedMs: Long,
        val bootCount: Int = BOOT_UNKNOWN,
        val signed: Boolean = true
    )

    /**
     * Saatin O ANDAKI tam durumu - zaman ve guven TEK OKUMADA birlikte gelir.
     *
     * Neden tek nesne: onceden `now()` ve `trusted()` ayri ayri cagriliyordu ve
     * arasinda bir senkron gerceklesirse ikisi TUTARSIZ olabiliyordu (1970 damgali
     * bir kayit "saat guvenilir" olarak faturaya girebiliyordu). Ayrica `now()`
     * guven bayragini hic okumadigi icin gecersiz kilinmis bir capadan uretilen
     * zaman sessizce her yere yazilabiliyordu.
     */
    data class Snapshot(val nowMs: Long, val trusted: Boolean, val reason: String)

    /**
     * Capa YAPISAL olarak kullanilabilir mi? (ayni acilis, sayac geri gitmemis)
     *
     * Bu "guvenilir" demek DEGILDIR - bkz. [snapshot]. Yapisal gecerlilik yalnizca
     * "bu capadan bir zaman uretmek, sistem saatinden uretmekten iyidir" demektir.
     */
    fun anchorValid(anchor: Anchor?, elapsedNow: Long, bootCount: Int = BOOT_UNKNOWN): Boolean {
        if (anchor == null) return false
        if (anchor.epochMs <= 0L || anchor.elapsedMs < 0L) return false

        // KESIN test: acilis sayaci iki tarafta da biliniyorsa yon/tolerans gerekmez.
        if (anchor.bootCount != BOOT_UNKNOWN && bootCount != BOOT_UNKNOWN) {
            if (anchor.bootCount != bootCount) return false
        }

        // Sayac okunamayan cihazlar icin eski sezgi; ayni acilis icinde de
        // monotonik sayacin geri gitmemesi gerektigi icin her durumda gecerli.
        return elapsedNow >= anchor.elapsedMs
    }

    /** Capa alindigindan bu yana gecen sure. Gecersiz capada [Long.MAX_VALUE]. */
    fun ageMs(anchor: Anchor?, elapsedNow: Long): Long =
        if (anchor == null || anchor.elapsedMs < 0L) Long.MAX_VALUE
        else (elapsedNow - anchor.elapsedMs).coerceAtLeast(0L)

    /**
     * Zamani VE guveni birlikte uret.
     *
     * @param invalidated disaridan (ornegin acilista) acikca gecersiz kilindi mi
     */
    fun snapshot(
        anchor: Anchor?,
        elapsedNow: Long,
        systemNow: Long,
        bootCount: Int = BOOT_UNKNOWN,
        invalidated: Boolean = false
    ): Snapshot {
        if (!anchorValid(anchor, elapsedNow, bootCount)) {
            return Snapshot(systemNow, false, if (anchor == null) "capa yok" else "yeniden baslatma")
        }
        val a = anchor!!
        val now = a.epochMs + (elapsedNow - a.elapsedMs)

        // Buradan sonrasi: capadan uretilen zamani KULLANIYORUZ (sistem saatinden
        // iyidir) ama guven vermiyoruz.
        val age = ageMs(a, elapsedNow)
        return when {
            invalidated -> Snapshot(now, false, "gecersiz kilindi")
            !a.signed -> Snapshot(now, false, "imzasiz kaynak (Date basligi)")
            age > MAX_ANCHOR_AGE_MS -> Snapshot(now, false, "capa ${age / 86_400_000} gunluk")
            else -> Snapshot(now, true, "")
        }
    }

    /** Geriye uyumlu kisayol. Guveni de gerekiyorsa [snapshot] kullanin. */
    fun now(anchor: Anchor?, elapsedNow: Long, systemNow: Long): Long =
        snapshot(anchor, elapsedNow, systemNow).nowMs

    // ------------------------------------------------ gelen zamanin kabulu

    /** Saat duzeltmesinde kabul edilen geriye sapma (NTP/gecikme paylari icin). */
    const val GERI_TOLERANS_MS = 10 * 60 * 1000L
    /** Derleme zamani zemininin altinda kabul edilen pay. */
    const val ZEMIN_PAYI_MS = 30L * 24 * 3600 * 1000
    /** Derlemeden sonra kabul edilen en uzak gelecek. */
    const val ILERI_SINIR_MS = 10L * 365 * 24 * 3600 * 1000

    enum class Karar {
        /** Kabul; capa yazilir. `latchUpdated` ise mandal da yukselir. */
        KABUL,
        /** Deger makul degil (yazilim var olmadan onceki bir an, ya da cok uzak gelecek). */
        RED_MAKUL_DEGIL,
        /** Son IMZALI zamanin gerisinde - suresi dolmus reklami diriltme girisimi olabilir. */
        RED_GERIYE,
        /** Imzasiz kaynak, gecerli bir IMZALI capayi ezemez. */
        RED_IMZASIZ_EZEMEZ
    }

    data class Kabul(val karar: Karar, val latchUpdated: Boolean = false)

    /**
     * Sunucudan gelen bir zamanin kabul edilip edilmeyecegi.
     *
     * Android'e bagimli DEGIL -> birim testi yazilabilir. Bu bilincli: kural, sistemin
     * en sessiz saldiri yuzeyi. Zamani geriye almak suresi DOLMUS veya IPTAL EDILMIS
     * reklamlari yeniden yayina sokar; ileriye almak ise - imzasiz kaynak mandali
     * yukseltebilirse - cihazi kalici olarak "saat supheli" durumuna kilitler ve
     * tarihli TUM kampanyalari yayindan dusurur. Iki yon de para kaybettirir, o yuzden
     * ikisi de testle sabitlenmeli.
     *
     * @param signed zaman IMZALI manifest govdesinden mi geldi (yoksa HTTP `Date`)
     * @param signedLatchMs son IMZALI zaman; 0 ise henuz yok
     * @param gecerliImzaliCapaVar su an yapisal olarak gecerli ve IMZALI bir capa var mi
     */
    fun kabulEdilirMi(
        epochMs: Long,
        signed: Boolean,
        signedLatchMs: Long,
        buildTimeMs: Long,
        gecerliImzaliCapaVar: Boolean
    ): Kabul {
        if (epochMs <= 0L) return Kabul(Karar.RED_MAKUL_DEGIL)

        if (epochMs < buildTimeMs - ZEMIN_PAYI_MS || epochMs > buildTimeMs + ILERI_SINIR_MS) {
            return Kabul(Karar.RED_MAKUL_DEGIL)
        }

        // Imzasiz kaynak, duran bir imzali capayi ezemez.
        if (!signed && gecerliImzaliCapaVar) return Kabul(Karar.RED_IMZASIZ_EZEMEZ)

        // Mandal: yalnizca IMZALI zamanla yukselir, ama HER kaynagi asagidan baglar.
        if (signedLatchMs > 0 && epochMs < signedLatchMs - GERI_TOLERANS_MS) {
            return Kabul(Karar.RED_GERIYE)
        }

        return Kabul(Karar.KABUL, latchUpdated = signed)
    }
}
