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
 *   P4  SURESI BITMIS kampanyalar -> hic oynatilamayacaklari icin en sonda
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
        /** null = tarihsiz. Gecmisteyse bu icerik HIC OYNAMAYACAK. */
        val validUntil: Long? = null,
        val remainingBytes: Long
    )

    /**
     * @param needs eksik (henuz hazir olmayan) icerikler
     * @param appUpdate bu cihaz icin bekleyen bir guncelleme var mi
     * @param appCritical guncelleme kritik mi (icerikten once indirilir)
     */
    fun order(
        needs: List<Need>,
        appUpdate: Boolean,
        appCritical: Boolean,
        /** Guvenilir "simdi". 0 ise saat supheli: tarih suzgeci UYGULANMAZ. */
        nowMs: Long = 0L
    ): List<String> {
        val out = ArrayList<String>(needs.size + 2)

        /*
         * SURESI BITMIS ICERIK EN SONA.
         *
         * Onceden P2 siralamasi yalnizca `validFrom` artan sirada yapiliyordu. Suresi
         * BITMIS bir kampanyanin validFrom'u tanimi geregi cok geride oldugu icin
         * listenin EN BASINA geciyordu: 3 dakikalik pencere, cihazda ASLA
         * oynatilamayacak (Eligibility zaten reddediyor) bir dosyaya harcaniyordu -
         * ustelik her ziyarette yeniden, cunku dosya bir daha hazir olmuyor.
         *
         * Sunucu artik suresi gecmis kampanyayi gondermiyor ama bu suzgeci de
         * tutuyoruz: iki taraf birbirine guvenmesin. Silmek yerine EN SONA atiyoruz
         * (yer kalirsa insin) - saat supheliyse ise hic suzmuyoruz, cunku o durumda
         * "suresi bitmis" karari guvenilir degildir.
         */
        fun suresiBitmis(n: Need): Boolean =
            nowMs > 0L && n.validUntil != null && nowMs > n.validUntil && !n.evergreen

        val gecerli = needs.filterNot(::suresiBitmis)
        val bitmis = needs.filter(::suresiBitmis)

        // P0 - evergreen'ler kendi aralarinda da kucukten buyuge
        out += gecerli.filter { it.evergreen }
            .sortedBy { it.remainingBytes }
            .map { it.id }

        // P1
        if (appUpdate && appCritical) out += APP_UPDATE

        // P2
        out += gecerli.filterNot { it.evergreen }
            .sortedWith(compareBy({ it.validFrom ?: Long.MAX_VALUE }, { it.remainingBytes }))
            .map { it.id }

        // P3
        if (appUpdate && !appCritical) out += APP_UPDATE

        // P4 - suresi bitmis: yalnizca her sey bittikten sonra
        out += bitmis.sortedBy { it.remainingBytes }.map { it.id }

        return out
    }
}
