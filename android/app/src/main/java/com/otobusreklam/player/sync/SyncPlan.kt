package com.otobusreklam.player.sync

/**
 * Indirme oncelik sirasi. Android'e bagimli DEGIL -> birim testi yazilabilir.
 *
 * Otobus noktada ~3 dakika duruyor; pencere yetmeyebilir. Bu yuzden HANGI BAYTIN
 * ONCE cekildigi kritik:
 *
 *   P0  eksik EVERGREEN        -> ekranin bos kalma riski her seyden onemli
 *   P1  KRITIK uygulama guncellemesi -> bozuk surumu duzeltmek en acil is
 *   P2  eksik kampanyalar      -> once yayina en yakin (validFrom),
 *                                 esitlikte KALAN BAYTI EN AZ olan
 *   P3  normal uygulama guncellemesi
 *
 * P2'deki ikinci kural bilincli: yarim 5 dosya yerine TAM 3 dosya cikarmak daha
 * degerlidir, cunku yarim dosya oynatilamaz.
 */
object SyncPlan {

    /** Uygulama guncellemesini temsil eden sentinel kimlik. */
    const val APP_UPDATE = "\u0000app-update"

    data class Need(
        val id: String,
        val evergreen: Boolean,
        /** null = tarihsiz; siralamada en sona atilir */
        val validFrom: Long?,
        val remainingBytes: Long
    )

    /**
     * @param needs eksik (henuz hazir olmayan) icerikler
     * @param appUpdate bu cihaz icin bekleyen bir guncelleme var mi
     * @param appCritical guncelleme kritik mi (icerikten once indirilir)
     */
    fun order(needs: List<Need>, appUpdate: Boolean, appCritical: Boolean): List<String> {
        val out = ArrayList<String>(needs.size + 1)

        // P0 - evergreen'ler kendi aralarinda da kucukten buyuge
        out += needs.filter { it.evergreen }
            .sortedBy { it.remainingBytes }
            .map { it.id }

        // P1
        if (appUpdate && appCritical) out += APP_UPDATE

        // P2
        out += needs.filterNot { it.evergreen }
            .sortedWith(compareBy({ it.validFrom ?: Long.MAX_VALUE }, { it.remainingBytes }))
            .map { it.id }

        // P3
        if (appUpdate && !appCritical) out += APP_UPDATE

        return out
    }
}
