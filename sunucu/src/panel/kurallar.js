/* eslint-env browser */
/*
 * PANELIN SAF KURALLARI - TEST EDILEBILIR OLMAK ICIN AYRI DOSYADA.
 *
 * Panelde blocker/major siniftan kusurlar bulundu (yenilemenin kampanyayi YANLIS
 * videoya baglamasi, bos catch'in donmus paneli calisir gostermesi) ama panel icin
 * HICBIR test altyapisi yoktu: duzeltmeler gerileyebilir ve bunu kimse gormezdi.
 *
 * Neden jsdom/karma degil: bu projenin bilincli bir kurali var - native/ekstra
 * bagimlilik yok (kurulum sorunu cikmasin). Bu dosya klasik bir <script> olarak
 * yukleniyor ve global `PanelKurallari` uzerinden kullaniliyor; testler onu
 * node:vm ile ayni sekilde yukluyor. Bagimlilik: sifir.
 *
 * Buraya YALNIZCA saf fonksiyonlar girer (DOM yok, fetch yok).
 */
(function (kok) {
  /**
   * HTML kacisi.
   *
   * NEDEN GUVENLIK KRITIK: bu paneldeki verilerin bir kismi CIHAZDAN geliyor
   * (heartbeat'teki lastError, playlistReason), bir kismi serbest metin (kampanya
   * basligi, reklamveren adi, yuklenen dosya adi). Kacis yapilmadan innerHTML'e
   * basilirsa, ele gecirilmis TEK bir otobus cihazi heartbeat'e
   * `<img src=x onerror=...>` yazarak panelde kod calistirabilir ve
   * localStorage'daki ADMIN TOKENINI calabilir - yani filoya APK yayimlama yetkisini.
   */
  function esc (value) {
    if (value === null || value === undefined) return ''
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
  }

  /**
   * Kampanyanin HESAPLANMIS durumu.
   *
   * Operator "kaydettim, neden yayinda degil?" diye sormasin: tabloda tarihler
   * gorunuyordu ama aktif/bekliyor/suresi gecmis/ters aralik ayrimi YOKTU - yani
   * hic yayinlanamayacak bir kampanya ile canli olani ayirt edilemiyordu.
   *
   * @param {object} c kampanya kaydi
   * @param {number} simdi epoch ms (test edilebilirlik icin parametre)
   */
  function kampanyaDurumu (c, simdi) {
    const now = Number.isFinite(simdi) ? simdi : Date.now()
    if (!c || c.enabled === false) return { metin: 'kapalı', renk: 'var(--dim)' }
    if (c.evergreen) return { metin: 'evergreen', renk: 'var(--ok)' }
    const f = c.validFrom ? Date.parse(c.validFrom) : null
    const u = c.validUntil ? Date.parse(c.validUntil) : null
    if (Number.isFinite(f) && Number.isFinite(u) && f >= u) return { metin: 'TERS ARALIK', renk: 'var(--bad)' }
    if (Number.isFinite(u) && u < now) return { metin: 'süresi geçmiş', renk: 'var(--bad)' }
    if (Number.isFinite(f) && f > now) return { metin: 'bekliyor', renk: 'var(--warn)' }
    return { metin: 'aktif', renk: 'var(--ok)' }
  }

  /**
   * datetime-local -> ISO. Bozuk deger SESSIZ gecmesin.
   *
   * Eskiden `new Date(v).toISOString()` cagrilari try blogunun DISINDAYDI: bir
   * RangeError'da Kaydet butonu HICBIR SEY yapmiyor ve log'a tek satir bile
   * dusmuyordu - operator tekrar tekrar basiyordu.
   */
  function isoTarih (ad, v) {
    if (!v) return null
    const d = new Date(v)
    if (Number.isNaN(d.getTime())) throw new Error(`${ad} tarihi okunamadı: "${v}"`)
    return d.toISOString()
  }

  /**
   * Icerik listesi DEGISTI mi? (secimi korumak icin)
   *
   * `<select>`'i her yenilemede yeniden kurmak tarayicinin selectedIndex'ini 0'a
   * dusuruyordu: operator formu doldururken (30 sn'yi gecmesi kacinilmaz) secili
   * video sessizce listenin ILK ogesine donuyor ve kampanya YANLIS videoya
   * baglaniyordu. 50 otobuste yanlis reklam, geri alma kanali yok.
   */
  function itemImzasi (items) {
    return (items || []).map((i) => i && i.sha256).join(',')
  }

  kok.PanelKurallari = { esc, kampanyaDurumu, isoTarih, itemImzasi }
})(typeof globalThis !== 'undefined' ? globalThis : this)
