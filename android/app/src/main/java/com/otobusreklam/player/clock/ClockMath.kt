package com.otobusreklam.player.clock

/**
 * Saat hesaplari. Android'e bagimli DEGIL -> birim testi yazilabilir.
 *
 * Fikir: sistem saatine guvenmiyoruz (ucuz stick'lerde pil destekli RTC yok,
 * guc kesilince sifirlanir). Onun yerine sunucudan alinan gercek zamani bir
 * "capa" olarak saklayip, uzerine MONOTONIK sayaci (elapsedRealtime) ekliyoruz.
 *
 * Monotonik sayac cihaz yeniden baslayinca sifirlanir; bunu tespit ettigimizde
 * capa gecersiz olur ve saat SUPHELI sayilir.
 */
object ClockMath {

    /** Sunucudan alinan gercek zaman + o andaki monotonik sayac degeri. */
    data class Anchor(val epochMs: Long, val elapsedMs: Long)

    /**
     * Capa hala gecerli mi?
     * Monotonik sayac GERIYE gittiyse cihaz yeniden baslamistir.
     */
    fun anchorValid(anchor: Anchor?, elapsedNow: Long): Boolean {
        if (anchor == null) return false
        if (anchor.epochMs <= 0L || anchor.elapsedMs < 0L) return false
        return elapsedNow >= anchor.elapsedMs
    }

    /**
     * Guvenilir "simdi".
     * Capa gecerliyse capa + gecen sure; degilse sistem saatine dusulur
     * (ve cagiran taraf saati SUPHELI olarak isaretler).
     */
    fun now(anchor: Anchor?, elapsedNow: Long, systemNow: Long): Long =
        if (anchorValid(anchor, elapsedNow)) {
            anchor!!.epochMs + (elapsedNow - anchor.elapsedMs)
        } else {
            systemNow
        }
}
