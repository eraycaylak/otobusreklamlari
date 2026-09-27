import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'

/**
 * Guvenlik davranislari.
 * Ayri dosyada: node:test her dosyayi AYRI SURECTE calistirir, boylece buradaki
 * deneme-siniri testi e2e testlerinin IP'sini kilitlemez.
 */

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'reklam-guv-'))
const ADMIN = 'guvenlik-test-tokeni'
let server, base, guard

before(async () => {
  process.env.NODE_ENV = 'test'
  process.env.DATA_DIR = DATA
  process.env.ADMIN_TOKEN = ADMIN
  process.env.SKIP_TRANSCODE = '1'
  process.env.CHUNK_SIZE = String(64 * 1024)

  execFileSync(process.execPath, ['scripts/anahtar-uret.js'], { cwd: process.cwd(), env: process.env })
  guard = await import('../src/guard.js')
  const { app } = await import('../src/index.js')
  await new Promise((r) => { server = app.listen(0, r) })
  base = `http://127.0.0.1:${server.address().port}`
})

after(() => {
  server?.close()
  fs.rmSync(DATA, { recursive: true, force: true })
})

const H = { authorization: `Bearer ${ADMIN}`, 'content-type': 'application/json' }

// ---------------------------------------------------------------- guard birimi

test('validId prototip kirletme adlarini reddeder', () => {
  for (const bad of ['__proto__', 'constructor', 'prototype']) {
    assert.equal(guard.validId(bad), false, `${bad} reddedilmeli`)
  }
})

test('validId yol kacisi denemelerini reddeder', () => {
  for (const bad of ['..', '.', '../evil', 'a/b', 'a\\b', 'a\u0000b', '']) {
    assert.equal(guard.validId(bad), false, `${JSON.stringify(bad)} reddedilmeli`)
  }
})

test('validId normal kimlikleri kabul eder', () => {
  for (const ok of ['OTOBUS-014', 'hat-14', 'k-abc123', 'a.b_c:d', 'A'.repeat(64)]) {
    assert.equal(guard.validId(ok), true, `${ok} kabul edilmeli`)
  }
  assert.equal(guard.validId('A'.repeat(65)), false, '64 karakterden uzun reddedilmeli')
})

test('csvSafe formul enjeksiyonunu notrlestirir', () => {
  // Excel'de "=" ile baslayan hucre FORMUL olarak calisir.
  assert.equal(guard.csvSafe('=cmd|calc'), "'=cmd|calc")
  assert.equal(guard.csvSafe('+1+1'), "'+1+1")
  assert.equal(guard.csvSafe('-2'), "'-2")
  assert.equal(guard.csvSafe('@SUM(A1)'), "'@SUM(A1)")
  assert.equal(guard.csvSafe('Kahve A.S.'), 'Kahve A.S.')
})

test('csvSafe ayirici ve satir sonunu temizler', () => {
  assert.equal(guard.csvSafe('a;b\nc\rd'), 'a b c d')
})

test('redactUrl tokeni gizler', () => {
  assert.equal(
    guard.redactUrl('/api/admin/report.csv?from=2026-01-01&token=gizli-deger'),
    '/api/admin/report.csv?from=2026-01-01&token=***'
  )
  assert.equal(guard.redactUrl('/content/abc.mp4'), '/content/abc.mp4')
})

// ---------------------------------------------------------------- HTTP davranisi

test('prototip kirletme kimligi HTTP uzerinden reddedilir', async () => {
  const r = await fetch(`${base}/api/admin/device`, {
    method: 'POST', headers: H, body: JSON.stringify({ id: '__proto__' })
  })
  assert.equal(r.status, 400)
  assert.match((await r.json()).error, /gecersiz cihaz id/)
})

test('yol kacisi iceren cihaz kimligi reddedilir', async () => {
  const r = await fetch(`${base}/api/admin/device`, {
    method: 'POST', headers: H, body: JSON.stringify({ id: '../../etc/passwd' })
  })
  assert.equal(r.status, 400)
})

test('gecersiz versionCode reddedilir', async () => {
  for (const v of ['1.5', 'abc', '-3', '0', '1e999']) {
    const r = await fetch(`${base}/api/admin/app?versionCode=${encodeURIComponent(v)}`, {
      method: 'POST', headers: { authorization: `Bearer ${ADMIN}` }, body: 'x'
    })
    assert.equal(r.status, 400, `versionCode=${v} reddedilmeli`)
  }
})

test('rapordaki reklamveren adi formul olarak calismaz', async () => {
  // Kotu niyetli reklamveren adiyla bir kampanya olustur
  const video = crypto.randomBytes(70 * 1024)
  const up = await fetch(`${base}/api/admin/upload?name=x.mp4`, {
    method: 'POST', headers: { authorization: `Bearer ${ADMIN}` }, body: video
  })
  const { item } = await up.json()

  await fetch(`${base}/api/admin/campaign`, {
    method: 'POST', headers: H,
    body: JSON.stringify({
      id: 'zararli', itemSha: item.sha256, advertiser: '=HYPERLINK("http://kotu","tikla")',
      validUntil: new Date(Date.now() + 86400e3).toISOString()
    })
  })

  const dev = await (await fetch(`${base}/api/admin/device`, {
    method: 'POST', headers: H, body: JSON.stringify({ id: 'OTOBUS-001' })
  })).json()

  await fetch(`${base}/api/v1/logs`, {
    method: 'POST',
    headers: { authorization: `Bearer ${dev.device.token}` },
    body: JSON.stringify({
      seq: 1, itemId: 'zararli', sha256: item.sha256,
      startedAt: new Date().toISOString(), durationMs: 1000, completed: true,
      playlistVersion: 1, clockTrusted: true
    })
  })

  const csv = await (await fetch(`${base}/api/admin/report.csv`, { headers: H })).text()
  assert.ok(csv.includes("'=HYPERLINK"), 'formul basindaki = notrlestirilmis olmali')
  assert.ok(!/;=HYPERLINK/.test(csv), 'ham formul CSV alanina girmemeli')
})

test('async handler hatasi sureci dusurmez, 500 doner', async () => {
  // Express 4 async handler'in REDDETMESINI yakalamaz; hata Node'un
  // unhandledRejection'ina duser ve Node 15+ varsayilan olarak SURECI OLDURUR.
  // tut() sarmalayicisi bunu Express'in hata zincirine baglar.
  const express = (await import('express')).default
  const mini = express()
  mini.get('/patla', guard.tut(async () => { throw new Error('beklenmedik hata') }))
  mini.get('/patla-ciplak', async () => { throw new Error('sarmalanmamis') })
  mini.use((err, req, res, next) => { // eslint-disable-line no-unused-vars
    res.status(500).json({ error: 'sunucu hatasi' })
  })

  const srv = mini.listen(0)
  await new Promise((r) => srv.once('listening', r))
  const u = `http://127.0.0.1:${srv.address().port}`

  let dusen = null
  const yakala = (e) => { dusen = e }
  process.on('unhandledRejection', yakala)

  const r = await fetch(`${u}/patla`)
  assert.equal(r.status, 500, 'sarmalanmis handler 500 donmeli')
  assert.deepEqual(await r.json(), { error: 'sunucu hatasi' })

  await new Promise((r2) => setTimeout(r2, 50))
  assert.equal(dusen, null, 'sarmalanmis handler yakalanmamis reddetme uretmemeli')

  process.off('unhandledRejection', yakala)
  srv.close()
})

test('admin.js icindeki TUM async route handler lari sarmalanmis', async () => {
  // Yeni bir async uc eklenip tut() unutulursa bu test kirilir.
  const fs = await import('node:fs')
  const kaynak = fs.readFileSync(new URL('../src/routes/admin.js', import.meta.url), 'utf8')

  const sarmalanmamis = []
  const desen = /adminRouter\.(get|post|put|delete)\(\s*'([^']+)'\s*,\s*([^)]*)/g
  let m
  while ((m = desen.exec(kaynak)) !== null) {
    const [, metod, yol, devami] = m
    // async handler mi? (ara middleware olabilir, bu yuzden satirin devamina bakiyoruz)
    const parca = kaynak.slice(m.index, m.index + 400)
    if (/async\s*\(/.test(parca) && !/tut\(\s*async/.test(parca)) {
      sarmalanmamis.push(`${metod.toUpperCase()} ${yol}`)
    }
  }
  assert.deepEqual(sarmalanmamis, [], `sarmalanmamis async uc(ler): ${sarmalanmamis.join(', ')}`)
})

/**
 * ONCEDEN: cihaz ve admin kimlik dogrulamasi AYNI IP sayacini paylasiyordu.
 *
 * Bu mimaride noktadaki TUM otobusler tek PtMP linkinin arkasinda, yani sunucuya AYNI
 * IP'den goruluyor. Tokeni iptal edilmis TEK BIR otobus, 15 dakikada 10 deneme yapip o
 * noktadaki BUTUN otobusleri 15 dakika kilitliyordu - her otobusun gunluk penceresi
 * birkac dakika oldugu icin bu, tek bozuk cihazin tum hattin yayinini durdurmasiydi.
 */
test('bozuk tokenli bir cihaz KOMSU otobusleri kilitlemez', async () => {
  guard.resetFailures()

  // Saglam bir "komsu otobus" kaydet
  const kayit = await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: H,
    body: JSON.stringify({ id: 'KOMSU-01', group: 'hat-1', label: 'komsu' })
  })
  assert.equal(kayit.status, 200)
  const deviceToken = (await kayit.json()).device.token
  assert.ok(deviceToken)

  // Bozuk cihaz ayni IP'den israrla deniyor
  for (let i = 0; i < 25; i++) {
    const r = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: 'Bearer bozuk-otobus-tokeni' } })
    assert.ok(r.status === 401 || r.status === 429)
  }

  // Ayni tokenle artik kilitli olmali: bozuk cihaz kendini sinirliyor
  const kendi = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: 'Bearer bozuk-otobus-tokeni' } })
  assert.equal(kendi.status, 429, 'israr eden cihaz kendi tokeniyle kilitlenmeli')

  // AMA saglam tokenli komsu otobus (ayni IP) hala calisabilmeli
  const komsu = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: `Bearer ${deviceToken}` } })
  assert.equal(komsu.status, 200, 'komsu otobus ayni IP yuzunden kilitlenmemeli')

  guard.resetFailures()
})

/**
 * ONCEDEN: app.set('trust proxy', true).
 *
 * Bu ayarla Express, X-Forwarded-For'un EN SOLDAKI degerini req.ip yapar - ve o
 * baslik ISTEMCI tarafindan yazilir. Saldirgan her istekte baska bir XFF yazarak
 * her seferinde temiz bir sayac alir, yani admin tokenini SINIRSIZ dener.
 */
test('X-Forwarded-For dondurerek deneme siniri ATLATILAMAZ', async () => {
  guard.resetFailures()

  let kilit = false
  for (let i = 0; i < 20; i++) {
    const r = await fetch(`${base}/api/admin/state`, {
      headers: { authorization: 'Bearer yanlis', 'x-forwarded-for': `10.1.2.${i}` }
    })
    if (r.status === 429) { kilit = true; break }
  }
  assert.ok(kilit, 'XFF degistirmek siniri atlatmamali')

  guard.resetFailures()
})

/**
 * APK PROVISION_SECRET'i GOMULU tasiyor. Kimlik dogrulamasiz servis edildiginde
 * sunucuya erisen herkes onu indirip filoya kendi cihazini sokabilir.
 */
test('APK ve icerik kimlik dogrulamasiz indirilemez', async () => {
  guard.resetFailures()

  const apk = await fetch(`${base}/app/olmayan.apk`)
  assert.equal(apk.status, 401, '/app tokensiz erisilememeli (404 bile sizdirmamali)')

  const icerik = await fetch(`${base}/content/olmayan.mp4`)
  assert.equal(icerik.status, 401, '/content varsayilan olarak tokensiz erisilememeli')

  guard.resetFailures()
})

/**
 * YAPILANDIRMA GERILEMESI TESTI - kod degil, dosya denetimi.
 *
 * Mimarinin acikca soz verdigi dayaniklilik: "PtMP linki koptugunda nokta onbellegi
 * BAYAT manifest servis eder, boylece otobusler yayina devam eder." Bu soz iki
 * ayarin BIRLIKTE dogru olmasina bagli ve ikisi ayri dosyada:
 *
 *   1. merkez  : manifeste `Cache-Control: no-store` koyuyor (CIHAZ onbelleklemesin)
 *   2. kutu    : `proxy_ignore_headers Cache-Control` ile bu basligi yok saymali
 *
 * (2) yoksa nginx yaniti HIC onbellege ALMAZ, proxy_cache_use_stale'in servis
 * edecegi bir kopya olusmaz ve dayaniklilik PRATIKTE YOKTUR. Bunu ancak link
 * gercekten koptugunda - yani en kotu anda, sahada - fark edebilirdiniz.
 *
 * Bu yuzden testle sabitliyoruz: ikisi birbirine bagli ve sessizce ayrisabilirler.
 */
test('nokta onbellegi manifesti GERCEKTEN onbellekleyebiliyor', async () => {
  // (1) Merkez tarafi: no-store gonderiyor mu?
  const kayit = await (await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...H },
    body: JSON.stringify({ id: 'OTOBUS-CACHE' })
  })).json()
  const r = await fetch(`${base}/api/v1/manifest`, {
    headers: { authorization: `Bearer ${kayit.device.token}` }
  })
  assert.equal(r.status, 200)
  assert.match(
    r.headers.get('cache-control') || '', /no-store/,
    'manifest CIHAZ tarafinda onbelleklenmemeli'
  )

  // (2) Kutu tarafi: nginx bu basligi yok sayiyor mu?
  const conf = fs.readFileSync(new URL('../../onbellek-kutusu/nginx.conf', import.meta.url), 'utf8')
  const manifestBloku = conf.slice(conf.indexOf('location = /api/v1/manifest'))
  const blok = manifestBloku.slice(0, manifestBloku.indexOf('\n    }'))

  assert.match(blok, /proxy_cache\s+manifest/, 'manifest onbellegi tanimli olmali')
  assert.match(
    blok, /proxy_ignore_headers[^;]*Cache-Control/,
    'proxy_ignore_headers Cache-Control OLMADAN nginx no-store yanitini onbellege ALMAZ - ' +
    'bayat manifest dayanikliligi pratikte yok olur'
  )
  assert.match(blok, /proxy_cache_use_stale/, 'bayat kopya servis edilebilmeli')
})

/**
 * BELGELERDEKI TEST SAYISI GERCEGI YANSITMALI.
 *
 * Kucuk bir sey gibi gorunuyor ama bu belgelerin isi GUVEN vermek: "43 test geciyor"
 * cumlesi, okuyanin kodu tek tek dogrulamadan ilerlemesini sagliyor. Sayi bir kez
 * kaydiginda okuyan bunu anlamaz; anladigi anda ise belgelerin GERI KALANINA da
 * guvenmemeye baslar - ki bu projede belgeler mimarinin neden boyle oldugunu tasiyan
 * asil yer. Sayiyi elle guncel tutmak kacinilmaz olarak basarisiz olur, o yuzden
 * testle bagliyoruz.
 */
test('belgelerdeki test sayisi gercek sayiyla ayni', () => {
  const dosyalar = ['e2e.test.js', 'guvenlik.test.js', 'transcode.test.js']
  let gercek = 0
  for (const d of dosyalar) {
    const kaynak = fs.readFileSync(new URL(`./${d}`, import.meta.url), 'utf8')
    gercek += (kaynak.match(/^test\(/gm) || []).length
  }

  for (const yol of ['../../README.md', '../../docs/kurulum-calistirma.md']) {
    const metin = fs.readFileSync(new URL(yol, import.meta.url), 'utf8')
    const iddialar = [...metin.matchAll(/(\d+)\s*test/g)].map((m) => Number(m[1]))
    // Sunucu test sayisini anan her yer dogru olmali. (65 = Kotlin testleri, ayri.)
    const sunucuIddialari = iddialar.filter((n) => n > 20 && n !== 65)
    for (const n of sunucuIddialari) {
      assert.equal(
        n, gercek,
        `${yol} icinde "${n} test" yaziyor ama gercek sayi ${gercek}. ` +
        'Test eklediyseniz belgeyi de guncelleyin.'
      )
    }
  }
})

test('cok fazla basarisiz admin denemesi IP kilitler', async () => {
  // Bu test EN SONDA: kilitlenen IP sonraki testleri etkilerdi.
  guard.resetFailures()
  let sawLock = false
  for (let i = 0; i < 14; i++) {
    const r = await fetch(`${base}/api/admin/state`, { headers: { authorization: 'Bearer yanlis' } })
    if (r.status === 429) { sawLock = true; break }
    assert.equal(r.status, 401)
  }
  assert.ok(sawLock, '10 basarisiz denemeden sonra 429 donmeli')

  // Kilit DOGRU tokeni de kapsar: saldirgan denemeye devam edemesin
  const r = await fetch(`${base}/api/admin/state`, { headers: H })
  assert.equal(r.status, 429)
})
