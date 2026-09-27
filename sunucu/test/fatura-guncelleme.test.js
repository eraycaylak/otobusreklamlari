import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'

/**
 * FATURA KURALLARI, GUNCELLEME KANALI VE GIRDI DOGRULAMASI.
 *
 * Bu uc alan projenin en pahali sessiz arizalarinin bulundugu yer:
 *  - fatura raporu: paranin tek sayisal dayanagi
 *  - /app + /app/rollout: bozuk bir surumden cikisin TEK yolu
 *  - girdi dogrulamasi: dogrulanmamis tek bir alan tum filoyu susturabiliyordu
 *
 * Ayri dosyada: node:test her dosyayi ayri surecte calistirir, yani kendi veri
 * dizini ve kendi deneme sayaci olur.
 */

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'reklam-fatura-'))
const ADMIN = 'fatura-test-tokeni'
let server, base, verifyEnvelope, publicKeyRaw, rolloutGroupOf

before(async () => {
  process.env.NODE_ENV = 'test'
  process.env.DATA_DIR = DATA
  process.env.ADMIN_TOKEN = ADMIN
  process.env.SKIP_TRANSCODE = '1'
  process.env.CHUNK_SIZE = String(64 * 1024)
  process.env.ROLLOUT_GROUPS = '4'

  execFileSync(process.execPath, ['scripts/anahtar-uret.js'], { cwd: process.cwd(), env: process.env })
  const c = await import('../src/crypto.js')
  verifyEnvelope = c.verifyEnvelope
  publicKeyRaw = c.publicKeyRaw
  rolloutGroupOf = (await import('../src/routes/admin.js')).rolloutGroupOf

  const { app } = await import('../src/index.js')
  await new Promise((r) => { server = app.listen(0, r) })
  base = `http://127.0.0.1:${server.address().port}`
})

after(() => {
  server?.close()
  fs.rmSync(DATA, { recursive: true, force: true })
})

const A = { authorization: `Bearer ${ADMIN}` }
const AJ = { ...A, 'content-type': 'application/json' }

async function cihaz (id, group = 'hat-14') {
  const r = await fetch(`${base}/api/admin/device`, {
    method: 'POST', headers: AJ, body: JSON.stringify({ id, group, label: id })
  })
  return (await r.json()).device.token
}

async function manifest (token) {
  const r = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: `Bearer ${token}` } })
  assert.equal(r.status, 200)
  const m = verifyEnvelope(await r.json(), publicKeyRaw())
  assert.ok(m, 'manifest imzasi dogrulanmali')
  return m
}

async function loglar (token, satirlar) {
  return fetch(`${base}/api/v1/logs`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/x-ndjson' },
    body: satirlar.map((x) => JSON.stringify(x)).join('\n')
  })
}

let itemSha, token14

test('kurulum: icerik + kampanya + cihaz', async () => {
  const up = await fetch(`${base}/api/admin/upload?name=kahve.mp4`, {
    method: 'POST', headers: A, body: crypto.randomBytes(200 * 1024)
  })
  assert.equal(up.status, 200)
  itemSha = (await up.json()).item.sha256

  const k = await fetch(`${base}/api/admin/campaign`, {
    method: 'POST',
    headers: AJ,
    body: JSON.stringify({
      id: 'kahve-30', itemSha, title: 'Kahve', advertiser: 'Kahve A.S.',
      groups: ['hat-14'], validUntil: '2030-12-31T23:59:00Z'
    })
  })
  assert.equal(k.status, 200)
  token14 = await cihaz('OTOBUS-014', 'hat-14')
})

// ---------------------------------------------------------------- #35 grup hedefleme

test('GRUP HEDEFLEME: yanlis hattaki cihaz kampanyayi ALMAZ, kisitsiz olani ALIR', async () => {
  /*
   * Negatif durum test edilmeden "grup hedefleme calisiyor" denemez: kurali
   * TAMAMEN kapatan bir hata da pozitif testi gecer. Iki iddia birlikte
   * gerekiyor - hem KESTIGINI hem FAZLA KESMEDIGINI dogruluyoruz.
   */
  await fetch(`${base}/api/admin/campaign`, {
    method: 'POST',
    headers: AJ,
    body: JSON.stringify({
      id: 'her-hat', itemSha, title: 'Kurum tanitimi', groups: [], evergreen: true
    })
  })

  const token99 = await cihaz('OTOBUS-099', 'hat-99')
  const m99 = await manifest(token99)
  assert.equal(m99.items.find((i) => i.id === 'kahve-30'), undefined,
    'hat-14 kampanyasi hat-99 otobusune SIZMAMALI')
  assert.ok(m99.items.find((i) => i.id === 'her-hat'),
    'grup kisiti olmayan kampanya HER hatta gorunmeli - aksi halde kural tamamen kapali olurdu')

  const m14 = await manifest(token14)
  assert.ok(m14.items.find((i) => i.id === 'kahve-30'), 'kendi hattinda gorunmeli')
})

// ---------------------------------------------------------------- #33 fatura kurallari

test('FATURA: yalnizca TAMAMLANMIS oynatmalar sayilir', async () => {
  /*
   * Faturanin tek sayisal dayanagi bu kural ve bugune kadar korumasizdi. Yarim
   * kalan oynatma (ekran kapandi, cihaz yeniden basladi) faturalanamaz.
   */
  await loglar(token14, [
    { seq: 1, epoch: 'e1', itemId: 'kahve-30', startedAt: '2026-09-10T08:00:00.000Z', durationMs: 30000, completed: true },
    { seq: 2, epoch: 'e1', itemId: 'kahve-30', startedAt: '2026-09-10T08:01:00.000Z', durationMs: 30000, completed: true },
    { seq: 3, epoch: 'e1', itemId: 'kahve-30', startedAt: '2026-09-10T08:02:00.000Z', durationMs: 12000, completed: false }
  ])

  const csv = await (await fetch(`${base}/api/admin/report.csv?from=2026-09-10&to=2026-09-10`, { headers: A })).text()
  const satir = csv.split('\n').find((l) => l.includes('OTOBUS-014'))
  assert.ok(satir, 'satir raporda olmali')
  const alan = satir.split(';')
  assert.equal(alan[4], '2', `oynatma sayisi 2 olmali (3 DEGIL), geldi: ${alan[4]}`)
  assert.equal(alan[5], '60', `toplam saniye yalnizca tamamlananlari toplamali, geldi: ${alan[5]}`)
})

test('FATURA: saati supheli kayit AYRI SUTUNDA sayilir ve GORUNUR', async () => {
  /*
   * En sinsi ariza buydu: dosya adi CIHAZIN saatinden turetildigi icin 1970'te
   * kalmis bir stick'in kayitlari raporun gun suzgecine HIC takilmiyor, yani
   * "saati supheli" sayaci da dahil hicbir yerde gorunmuyordu. Dosya adi artik
   * SUNUCU gununden geliyor; satir gorunur ve isaretli olmali.
   */
  const t = await cihaz('OTOBUS-SAAT', 'hat-14')
  await loglar(t, [
    { seq: 1, epoch: 'x1', itemId: 'kahve-30', startedAt: '1970-01-01T00:00:05.000Z', durationMs: 30000, completed: true, clockTrusted: false }
  ])

  const bugun = new Date().toISOString().slice(0, 10)
  const csv = await (await fetch(`${base}/api/admin/report.csv?from=${bugun}&to=${bugun}`, { headers: A })).text()
  const satir = csv.split('\n').find((l) => l.includes('OTOBUS-SAAT'))
  assert.ok(satir, '1970 saatli kayit RAPORDA GORUNMELI - eskiden tamamen kayboluyordu')
  const alan = satir.split(';')
  assert.equal(alan[3], bugun, 'saat guvenilir degilse gun SUNUCU saatinden yazilmali')
  assert.equal(alan[6], '1', 'saati_supheli_kayit 1 olmali')
})

test('FATURA: clockTrusted alani HIC yoksa supheli SAYILMAZ', async () => {
  // `=== false` kontrolunun bilincli oldugunu kilitler: alan yoksa (eski surum)
  // kaydi supheli saymak tum eski verileri lekelerdi.
  const t = await cihaz('OTOBUS-ESKI', 'hat-14')
  await loglar(t, [
    { seq: 1, epoch: 'y1', itemId: 'kahve-30', startedAt: '2026-09-11T09:00:00.000Z', durationMs: 30000, completed: true }
  ])
  const csv = await (await fetch(`${base}/api/admin/report.csv?from=2026-09-11&to=2026-09-11`, { headers: A })).text()
  const satir = csv.split('\n').find((l) => l.includes('OTOBUS-ESKI'))
  assert.ok(satir)
  assert.equal(satir.split(';')[6], '0', 'clockTrusted yoksa supheli sayilmamali')
})

test('FATURA: from/to ve campaign suzgecleri gercekten suzer', async () => {
  // Operator her fatura doneminde tam olarak bu suzgecleri kullanacak.
  const disarida = await (await fetch(`${base}/api/admin/report.csv?from=2026-09-10&to=2026-09-10`, { headers: A })).text()
  assert.ok(!disarida.includes('2026-09-11'), 'aralik disindaki gun raporda OLMAMALI')

  const yok = await (await fetch(`${base}/api/admin/report.csv?campaign=olmayan-kampanya`, { headers: A })).text()
  assert.equal(yok.trim().split('\n').length, 1, 'baska kampanya suzgeci yalnizca baslik satiri birakmali')

  const var_ = await (await fetch(`${base}/api/admin/report.csv?campaign=kahve-30`, { headers: A })).text()
  assert.ok(var_.includes('kahve-30'), 'kendi kampanyasi gorunmeli')
})

// ---------------------------------------------------------------- #36 guncelleme kanali

test('GUNCELLEME: APK yayimi uctan uca calisir ve manifeste girer', async () => {
  /*
   * Bozuk bir surumden cikisin (fix-forward) TEK yolu bu kanal ve bugune kadar
   * basarili yolunun tek testi yoktu.
   */
  const apk = crypto.randomBytes(150 * 1024)
  const r = await fetch(`${base}/api/admin/app?versionCode=42&versionName=1.4.0&rolloutGroup=4&critical=1`, {
    method: 'POST', headers: A, body: apk
  })
  assert.equal(r.status, 200)
  const { app } = await r.json()
  assert.equal(app.versionCode, 42)
  assert.equal(app.rolloutGroup, 4)
  assert.equal(app.critical, true)
  assert.ok(app.chunkCount >= 2, 'cok parcali olmali (CHUNK_SIZE 64 KB)')

  const m = await manifest(token14)
  assert.equal(m.app.versionCode, 42)
  assert.equal(m.app.url, 'app/reklam-42.apk')
  assert.equal(m.app.rolloutGroup, 4)
  assert.equal(m.app.critical, true)
  assert.equal(m.app.chunks.length, app.chunkCount)

  // Dosya gercekten servis edilebiliyor mu (token ile)?
  const d = await fetch(`${base}/app/reklam-42.apk`, { headers: { authorization: `Bearer ${token14}` } })
  assert.equal(d.status, 200)
  assert.equal(Number(d.headers.get('content-length')), apk.length)
})

test('GUNCELLEME: ayni versionCode force olmadan 409, force ile kabul', async () => {
  const r = await fetch(`${base}/api/admin/app?versionCode=42&versionName=1.4.0`, {
    method: 'POST', headers: A, body: crypto.randomBytes(1024)
  })
  assert.equal(r.status, 409, 'ayni surumu sessizce ezmek, o pencerede gelen otobusun dogrulamasini bozardi')

  const f = await fetch(`${base}/api/admin/app?versionCode=42&versionName=1.4.0-b&force=1`, {
    method: 'POST', headers: A, body: crypto.randomBytes(80 * 1024)
  })
  assert.equal(f.status, 200)
})

test('GUNCELLEME: basarisiz yukleme YAYINDAKI APK dosyasini SILMEZ', async () => {
  /*
   * Eski hali govdeyi dogrudan yayindaki dosyanin ustune akitiyor ve hata yolunda
   * HEDEFI SILIYORDU - ama s.app degismedigi icin manifest o dosyayi reklam etmeye
   * devam ediyordu. Govdesiz tek bir istek tum filonun APK'sini 404'e ceviriyordu.
   */
  const dosya = path.join(DATA, 'app', 'reklam-42.apk')
  const oncekiBoyut = fs.statSync(dosya).size

  const bos = await fetch(`${base}/api/admin/app?versionCode=42&force=1`, { method: 'POST', headers: A })
  assert.equal(bos.status, 400, 'bos govde reddedilmeli')

  assert.ok(fs.existsSync(dosya), 'YAYINDAKI APK SILINMEMELI')
  assert.equal(fs.statSync(dosya).size, oncekiBoyut, 'yayindaki APK degismemis olmali')

  const m = await manifest(token14)
  const d = await fetch(`${base}/${m.app.url}`, { headers: { authorization: `Bearer ${token14}` } })
  assert.equal(d.status, 200, 'manifestin reklam ettigi APK indirilebilir olmali')
})

test('GUNCELLEME: gecersiz rolloutGroup 400 - sessizce kapatamaz', async () => {
  for (const kotu of ['hepsi', '0', '-1', '1e999', '2.5', '99']) {
    const r = await fetch(`${base}/api/admin/app/rollout`, {
      method: 'POST', headers: AJ, body: JSON.stringify({ rolloutGroup: kotu })
    })
    assert.equal(r.status, 400, `rolloutGroup="${kotu}" reddedilmeliydi (guncellemeyi tum filoya kapatirdi)`)
  }
  const ok = await fetch(`${base}/api/admin/app/rollout`, {
    method: 'POST', headers: AJ, body: JSON.stringify({ rolloutGroup: 2 })
  })
  assert.equal(ok.status, 200)
  assert.equal((await ok.json()).app.rolloutGroup, 2)
})

test('GUNCELLEME: /app yayimda da gecersiz rolloutGroup reddedilir', async () => {
  const r = await fetch(`${base}/api/admin/app?versionCode=43&rolloutGroup=0`, {
    method: 'POST', headers: A, body: crypto.randomBytes(1024)
  })
  assert.equal(r.status, 400)
  assert.ok(!fs.existsSync(path.join(DATA, 'app', 'reklam-43.apk')), 'reddedilen yayim dosya birakmamali')
})

// ---------------------------------------------------------------- #37 rollout sozlesmesi

test('ROLLOUT SOZLESMESI: sunucu tarafi Kotlin tarafiyla AYNI tabloyu uretir', () => {
  /*
   * Bu tablo android/.../RolloutGroupTest.kt icindekinin BIREBIR AYNISI.
   * Iki taraftan yalnizca biri kilitliyse "iki taraf ayni sonucu uretir" iddiasi
   * yarim kalir: sunucu cihazi 2. grupta sanirken cihaz kendini 3. grupta sanir ve
   * kademeli yayim ongorulemez olur.
   */
  const tablo = [
    ['OTOBUS-014', 4], ['OTOBUS-015', 4], ['OTOBUS-001', 4],
    ['hat-14-a', 4], ['A', 4], ['', 4],
    ['OTOBUS-014', 1], ['OTOBUS-014', 2], ['OTOBUS-014', 8],
    ['polygenelubricants', 4], ['polygenelubricants', 1], ['polygenelubricants', 7]
  ]
  for (const [id, n] of tablo) {
    const g = rolloutGroupOf(id, n)
    assert.ok(Number.isInteger(g) && g >= 1 && g <= n, `${id}/${n} -> ${g} araligin disinda`)
  }
  // Int.MIN_VALUE ozel ele alisi: 'polygenelubricants' hash'i tam -2147483648.
  assert.equal(rolloutGroupOf('polygenelubricants', 4), 1,
    'Int.MIN_VALUE durumu iki tarafta ayni sonucu vermeli')
  assert.equal(rolloutGroupOf('OTOBUS-014', 1), 1, 'tek grup varsa herkes 1. grupta')
})

test('ROLLOUT SOZLESMESI: cihaz kaydi HTTP uzerinden de ayni grubu verir', async () => {
  const r = await fetch(`${base}/api/admin/device`, {
    method: 'POST', headers: AJ, body: JSON.stringify({ id: 'polygenelubricants' })
  })
  const { device } = await r.json()
  assert.equal(device.rolloutGroup, rolloutGroupOf('polygenelubricants', 4),
    'HTTP yolu ile saf fonksiyon ayni sonucu vermeli')
  assert.equal(device.rolloutPinned, false, 'otomatik atama kanarya sayilmamali')
})

// ---------------------------------------------------------------- girdi dogrulamasi

test('DOGRULAMA: itemSha __proto__ reddedilir - tum filonun manifestini bozardi', async () => {
  const r = await fetch(`${base}/api/admin/campaign`, {
    method: 'POST', headers: AJ,
    body: JSON.stringify({ itemSha: '__proto__', validUntil: '2030-12-31T00:00:00Z' })
  })
  assert.equal(r.status, 400, '__proto__ varlik kontrolunu asip manifeste eksik alanli oge sokuyordu')
})

test('DOGRULAMA: ayristirilamayan validUntil reddedilir', async () => {
  // Bunlar Date.parse ile de okunamiyor: kabul edilseler cihazda `Instant.parse`
  // duserdi, validUntil null olurdu ve ucretli reklam HIC oynamazdi.
  for (const kotu of ['31.12.2026', 'yarin', '', '2026-13-45T00:00:00Z']) {
    const r = await fetch(`${base}/api/admin/campaign`, {
      method: 'POST', headers: AJ,
      body: JSON.stringify({ id: `t-${Buffer.from(kotu).toString('hex') || 'bos'}`, itemSha, validUntil: kotu })
    })
    assert.equal(r.status, 400, `validUntil="${kotu}" kabul edilmemeli - reklam HIC oynamazdi`)
  }
})

test('DOGRULAMA: salt tarih KABUL EDILIR ama Instant.parse bicimine cevrilir', async () => {
  /*
   * "2026-12-31" operatorun yazmasi cok muhtemel bir deger ve Date.parse onu
   * okuyabiliyor. Reddetmek gereksiz surtunme olurdu; ASIL risk bu degerin oldugu
   * gibi saklanmasiydi - cihazdaki `Instant.parse("2026-12-31")` duser, validUntil
   * null olur ve kampanya KALICI OLARAK uygun olmaz. Normalize ederek hem kabul
   * ediyoruz hem cihazin okuyabilecegi bicime ceviriyoruz.
   */
  const r = await fetch(`${base}/api/admin/campaign`, {
    method: 'POST', headers: AJ,
    body: JSON.stringify({ id: 'salt-tarih', itemSha, validUntil: '2030-12-31' })
  })
  assert.equal(r.status, 200)
  assert.equal((await r.json()).campaign.validUntil, '2030-12-31T00:00:00.000Z')
})

test('DOGRULAMA: kabul edilen tarih ISO-8601 olarak NORMALIZE edilir', async () => {
  // Cihaz katı `Instant.parse` kullaniyor; sakladigimiz deger her zaman onun
  // kabul ettigi bicimde olmali.
  const r = await fetch(`${base}/api/admin/campaign`, {
    method: 'POST', headers: AJ,
    body: JSON.stringify({ id: 'norm-1', itemSha, validFrom: '2026-10-01T06:00:00+03:00', validUntil: '2030-01-01T00:00:00Z' })
  })
  assert.equal(r.status, 200)
  const { campaign } = await r.json()
  assert.equal(campaign.validFrom, '2026-10-01T03:00:00.000Z')
  assert.equal(campaign.validUntil, '2030-01-01T00:00:00.000Z')
})

test('DOGRULAMA: ters tarih araligi reddedilir', async () => {
  const r = await fetch(`${base}/api/admin/campaign`, {
    method: 'POST', headers: AJ,
    body: JSON.stringify({ id: 'ters-1', itemSha, validFrom: '2030-01-02T00:00:00Z', validUntil: '2030-01-01T00:00:00Z' })
  })
  assert.equal(r.status, 400, 'hic yayinlanamayacak kampanya kabul edilmemeli')
})

test('DOGRULAMA: DELETE __proto__ "silindi" demez', async () => {
  for (const uc of ['campaign', 'device']) {
    const r = await fetch(`${base}/api/admin/${uc}/__proto__`, { method: 'DELETE', headers: A })
    assert.equal(r.status, 400, `DELETE /${uc}/__proto__ ok:true donmemeli`)
  }
})

test('DOGRULAMA: /temizlik saklama suresi dogrulanir - her seyi silemez', async () => {
  for (const kotu of [{ kanitGun: '90 gun' }, { kanitGun: 0 }, { gecmisGun: 0 }, { gecmisGun: 'hepsi' }, { kanitGun: 3 }]) {
    const r = await fetch(`${base}/api/admin/temizlik`, {
      method: 'POST', headers: AJ, body: JSON.stringify({ uygula: true, ...kotu })
    })
    assert.equal(r.status, 400, `${JSON.stringify(kotu)} reddedilmeliydi - TUM kanit/gecmis silinirdi`)
  }
})

test('TEMIZLIK: yetim incoming ve content dosyalari bulunur', async () => {
  /*
   * Diski gercekten dolduran sey kayit disi kalanlar: yarida kesilen 2 GB'lik bir
   * yukleme (incoming/) ya da ingest ile save arasinda kesinti olan bir icerik
   * (content/). Uc bunlari hic goremiyordu.
   */
  const eski = Date.now() - 48 * 3600 * 1000
  const yetimIn = path.join(DATA, 'incoming', 'yarim-yukleme.mov')
  fs.writeFileSync(yetimIn, crypto.randomBytes(4096))
  fs.utimesSync(yetimIn, new Date(eski), new Date(eski))
  const yetimIc = path.join(DATA, 'content', 'a'.repeat(64) + '.mp4')
  fs.writeFileSync(yetimIc, crypto.randomBytes(2048))

  const prova = await (await fetch(`${base}/api/admin/temizlik`, {
    method: 'POST', headers: AJ, body: '{}'
  })).json()
  assert.ok(prova.yetimYuklemeler.some((x) => x.dosya === 'yarim-yukleme.mov'), 'yetim yukleme kuru provada gorunmeli')
  assert.ok(prova.yetimIcerikler.some((x) => x.dosya.startsWith('aaaa')), 'yetim icerik kuru provada gorunmeli')
  assert.ok(fs.existsSync(yetimIn), 'kuru provada SILINMEMELI')

  await fetch(`${base}/api/admin/temizlik`, {
    method: 'POST', headers: AJ, body: JSON.stringify({ uygula: true })
  })
  assert.ok(!fs.existsSync(yetimIn), 'uygula:true ile yetim yukleme silinmeli')
  assert.ok(!fs.existsSync(yetimIc), 'uygula:true ile yetim icerik silinmeli')
  assert.ok(fs.existsSync(path.join(DATA, 'content', `${itemSha}.mp4`)), 'KAYITLI icerik korunmali')
})

test('LOG: startedAt SAYI gelse bile parti 500 vermez ve ACK doner', async () => {
  /*
   * Eski hali: startedAt dogrulanmadigi icin store.js `.slice` cagirinca TypeError
   * atiyor, istek 500 donuyor ve cihaz ACK ALMADIGI icin satirlari SILMIYORDU - her
   * pencerede ayni partiyi yeniden yukluyor, yine 500 aliyordu. O otobusun fatura
   * verisi sunucuya HIC ulasmiyordu.
   */
  const t = await cihaz('OTOBUS-SAYI', 'hat-14')
  const r = await loglar(t, [
    { seq: 1, epoch: 'z1', itemId: 'kahve-30', startedAt: 1789000000000, durationMs: 30000, completed: true }
  ])
  assert.equal(r.status, 200, 'bozuk tek alan tum partiyi ve ACK`i bloklamamali')
  assert.equal((await r.json()).ackSeq, 1)

  const bugun = new Date().toISOString().slice(0, 10)
  const csv = await (await fetch(`${base}/api/admin/report.csv?from=${bugun}&to=${bugun}`, { headers: A })).text()
  assert.ok(csv.includes('OTOBUS-SAYI'), 'kayit yine de faturada gorunmeli')
})

test('LOG: startedAt yol kacisi logs dizini disina dosya ACMAZ', async () => {
  const t = await cihaz('OTOBUS-KACIS', 'hat-14')
  const r = await loglar(t, [
    { seq: 1, epoch: 'k1', itemId: 'kahve-30', startedAt: '../../../tmp/kacis', durationMs: 1000, completed: true }
  ])
  assert.equal(r.status, 200)
  const dosyalar = fs.readdirSync(path.join(DATA, 'logs'))
  for (const f of dosyalar) {
    assert.match(f, /^\d{4}-\d{2}-\d{2}\.ndjson$/, `logs altinda beklenmeyen dosya: ${f}`)
  }
})

test('HEARTBEAT: beyaz liste bilinmeyen ve sisirilmis alanlari keser', async () => {
  /*
   * Kurcalanmis tek bir stick `lastError` alanina her heartbeat'te 250 KB yazarak
   * gunde onlarca MB birikim uretebiliyordu; gecmis dosyalari 180 gun silinmiyor ve
   * pencere raporu onlarin TAMAMINI RAM'e okuyor.
   */
  const t = await cihaz('OTOBUS-SISME', 'hat-14')
  const r = await fetch(`${base}/api/v1/heartbeat`, {
    method: 'POST',
    headers: { authorization: `Bearer ${t}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      readyItems: 2, lastError: 'x'.repeat(200 * 1024), uydurmaAlan: 'y'.repeat(50 * 1024)
    })
  })
  assert.equal(r.status, 200)
  const kayit = JSON.parse(fs.readFileSync(path.join(DATA, 'heartbeat', 'OTOBUS-SISME.json'), 'utf8'))
  assert.equal(kayit.lastError.length, 500, 'serbest metin kirpilmali')
  assert.equal(kayit.uydurmaAlan, undefined, 'beyaz listede olmayan alan kaliciya yazilmamali')
  assert.equal(kayit.readyItems, 2, 'beklenen alan gecmeli')
})
