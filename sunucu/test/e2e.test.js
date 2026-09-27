import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'

/**
 * Uctan uca test: sunucunun cihazin yasayacagi akisi dogru destekledigini dogrular.
 * En kritik senaryo: 3 dakikalik pencere yetmedi -> Range ile kaldigi yerden devam.
 */

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'reklam-test-'))
const ADMIN = 'test-admin-token'
let server, base, verifyEnvelope, publicKeyRaw

before(async () => {
  process.env.NODE_ENV = 'test'
  process.env.DATA_DIR = DATA
  process.env.ADMIN_TOKEN = ADMIN
  process.env.SKIP_TRANSCODE = '1'      // ortamda ffmpeg yok; girdi oldugu gibi kabul edilir
  process.env.CHUNK_SIZE = String(64 * 1024) // testte kucuk parca: cok parcali akisi zorlamak icin

  execFileSync(process.execPath, ['scripts/anahtar-uret.js'], { cwd: process.cwd(), env: process.env })

  const cryptoMod = await import('../src/crypto.js')
  verifyEnvelope = cryptoMod.verifyEnvelope
  publicKeyRaw = cryptoMod.publicKeyRaw

  const { app } = await import('../src/index.js')
  await new Promise((r) => { server = app.listen(0, r) })
  base = `http://127.0.0.1:${server.address().port}`
})

after(() => {
  server?.close()
  fs.rmSync(DATA, { recursive: true, force: true })
})

const adminHeaders = { authorization: `Bearer ${ADMIN}` }

test('admin token olmadan erisim reddedilir', async () => {
  const r = await fetch(`${base}/api/admin/state`)
  assert.equal(r.status, 401)
})

test('gecersiz cihaz tokeni reddedilir', async () => {
  const r = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: 'Bearer uydurma' } })
  assert.equal(r.status, 401)
})

let deviceToken, itemSha, videoBytes

test('cihaz kaydi token uretir', async () => {
  const r = await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'OTOBUS-014', label: '34 ABC 123', group: 'hat-14' })
  })
  assert.equal(r.status, 200)
  const { device } = await r.json()
  deviceToken = device.token
  assert.ok(deviceToken && deviceToken.length > 20)
  assert.ok(device.rolloutGroup >= 1)
})

test('icerik yuklenir, parcalanir, icerik adresli isim alir', async () => {
  // 300 KB'lik sozde video: CHUNK_SIZE 64 KB -> 5 parca
  videoBytes = crypto.randomBytes(300 * 1024)
  const r = await fetch(`${base}/api/admin/upload?name=reklam.mp4`, {
    method: 'POST', headers: adminHeaders, body: videoBytes
  })
  assert.equal(r.status, 200)
  const { item } = await r.json()
  itemSha = item.sha256
  assert.equal(item.size, videoBytes.length)
  assert.equal(item.chunkCount, 5)
  assert.equal(itemSha, crypto.createHash('sha256').update(videoBytes).digest('hex'))
  assert.equal(item.file, `content/${itemSha}.mp4`)
})

test('bitis tarihi olmayan kampanya REDDEDILIR', async () => {
  const r = await fetch(`${base}/api/admin/campaign`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ itemSha, title: 'Tarihsiz' })
  })
  assert.equal(r.status, 400)
  assert.match((await r.json()).error, /validUntil/)
})

test('kampanya olusturulur', async () => {
  const r = await fetch(`${base}/api/admin/campaign`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({
      id: 'kahve-30',
      itemSha,
      title: 'Kahve kampanyasi',
      advertiser: 'Kahve A.S.',
      validFrom: new Date(Date.now() - 3600e3).toISOString(),
      validUntil: new Date(Date.now() + 7 * 86400e3).toISOString(),
      weight: 2,
      groups: ['hat-14'],
      dayparts: ['07:00-10:00']
    })
  })
  assert.equal(r.status, 200)
})

test('manifest imzali gelir ve imza dogrulanir', async () => {
  const r = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: `Bearer ${deviceToken}` } })
  assert.equal(r.status, 200)
  assert.ok(r.headers.get('date'), 'Date basligi saat senkronu icin zorunlu')

  const envelope = await r.json()
  assert.equal(envelope.alg, 'ed25519')

  const m = verifyEnvelope(envelope)
  assert.ok(m, 'imza dogrulanmali')
  assert.equal(m.deviceId, 'OTOBUS-014')
  assert.equal(m.deviceGroup, 'hat-14')
  assert.equal(m.items.length, 1)
  assert.equal(m.items[0].id, 'kahve-30')
  assert.equal(m.items[0].chunks.length, 5)
  assert.equal(m.policy.parallelChunks, 2)

  /*
   * SAAT DILIMI IMZALI GOVDEDE OLMAK ZORUNDA.
   *
   * Daypart ("07:00-10:00") bu dilime gore uygulanir. Cihaz onceden kendi dilimini
   * kullaniyordu; ucuz stick'lerde bu genelde UTC'dir ve Turkiye icin 3 SAAT kayma
   * demektir - sabah kusagi icin satilan reklam ogleden sonra doner. Deger imzanin
   * ICINDE: aksi halde aga erisen biri onu degistirip yayin saatini kaydirabilirdi.
   */
  assert.ok(m.timezone, 'manifest saat dilimi tasimali')
  assert.equal(m.timezone, 'Europe/Istanbul')

  /*
   * IMZALI serverTime ZORUNLU.
   *
   * Cihazin GUVENILIR saat capasi yalnizca bu alandan kurulur. Date basligi imzanin
   * disindadir ve yalnizca ZAYIF capa yapar (1970 damgasini duzeltir, bitis tarihi
   * zorlamasini etkilemez). Bu alan kaybolursa hicbir cihaz bir daha guvenilir saate
   * ulasamaz, yani tarihli TUM kampanyalar sessizce yayindan duser.
   */
  assert.ok(m.serverTime, 'imzali serverTime zorunlu')
  assert.ok(Math.abs(Date.parse(m.serverTime) - Date.now()) < 60_000, 'serverTime taze olmali')
})

test('kurcalanmis manifest imzasi REDDEDILIR', async () => {
  const r = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: `Bearer ${deviceToken}` } })
  const envelope = await r.json()

  // Saldirgan icerigi degistirsin: imza artik tutmamali
  const payload = JSON.parse(Buffer.from(envelope.payload, 'base64').toString())
  payload.items[0].sha256 = 'f'.repeat(64)
  envelope.payload = Buffer.from(JSON.stringify(payload)).toString('base64')

  assert.equal(verifyEnvelope(envelope), null, 'degistirilmis manifest kabul edilmemeli')
})

test('KRITIK: pencere yetmedi senaryosu - Range ile parcali indirip devam etme', async () => {
  const r = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: `Bearer ${deviceToken}` } })
  const m = verifyEnvelope(await r.json())
  const item = m.items[0]

  const assembled = Buffer.alloc(item.size)

  // 1. ziyaret: otobus 3 dakika durdu, sadece 2 parca indi
  for (const c of item.chunks.slice(0, 2)) {
    const res = await fetch(`${base}/${item.file}`, {
      // Token: /content varsayilan olarak kimlik dogrulamasi ister (bkz. index.js).
      headers: { range: `bytes=${c.offset}-${c.offset + c.len - 1}`, authorization: `Bearer ${deviceToken}` }
    })
    assert.equal(res.status, 206, 'sunucu 206 Partial Content donmeli')
    assert.equal(res.headers.get('content-range'), `bytes ${c.offset}-${c.offset + c.len - 1}/${item.size}`)
    const buf = Buffer.from(await res.arrayBuffer())
    assert.equal(buf.length, c.len)
    assert.equal(crypto.createHash('sha256').update(buf).digest('hex'), c.sha256, 'parca hash i tutmali')
    buf.copy(assembled, c.offset)
  }

  // 2. ziyaret: KALAN parcalar kaldigi yerden
  for (const c of item.chunks.slice(2)) {
    const res = await fetch(`${base}/${item.file}`, {
      headers: { range: `bytes=${c.offset}-${c.offset + c.len - 1}`, authorization: `Bearer ${deviceToken}` }
    })
    assert.equal(res.status, 206)
    const buf = Buffer.from(await res.arrayBuffer())
    assert.equal(crypto.createHash('sha256').update(buf).digest('hex'), c.sha256)
    buf.copy(assembled, c.offset)
  }

  // Birlestirilen dosya orijinalle birebir ayni olmali
  assert.equal(crypto.createHash('sha256').update(assembled).digest('hex'), item.sha256)
  assert.ok(assembled.equals(videoBytes))
})

test('oynatma loglari gzip NDJSON olarak alinir ve tekrarlar elenir', async () => {
  const rows = [1, 2, 3].map((seq) => ({
    seq,
    itemId: 'kahve-30',
    sha256: itemSha,
    startedAt: new Date().toISOString(),
    durationMs: 30000,
    completed: true,
    playlistVersion: 2,
    clockTrusted: true
  }))
  const body = zlib.gzipSync(Buffer.from(rows.map((r) => JSON.stringify(r)).join('\n')))

  const r1 = await fetch(`${base}/api/v1/logs`, {
    method: 'POST',
    headers: { authorization: `Bearer ${deviceToken}`, 'content-encoding': 'gzip', 'content-type': 'application/x-ndjson' },
    body
  })
  assert.equal(r1.status, 200)
  const c1 = await r1.json()
  assert.equal(c1.ackSeq, 3)
  assert.equal(c1.accepted, 3)

  // Cihaz ACK'i alamadi ve ayni paketi tekrar gonderdi -> tekrar yazilmamali
  const r2 = await fetch(`${base}/api/v1/logs`, {
    method: 'POST',
    headers: { authorization: `Bearer ${deviceToken}`, 'content-encoding': 'gzip' },
    body
  })
  const c2 = await r2.json()
  assert.equal(c2.ackSeq, 3)
  assert.equal(c2.accepted, 0, 'ayni paket tekrar gonderilirse yazilmamali')
})

/**
 * CIFTE FATURALAMA - gecici bir ag sorunundan dogan kalici para hatasi.
 *
 * Ayristirilabilir satir icermeyen bir govde (kirpilmis gzip, bozulmus istek, eski
 * surum) su zinciri tetikliyordu:
 *   rows bos -> epoch "" -> kayitli epoch'tan farkli -> "yeni kurulum" ->
 *   seenSeq BOS EPOCH'LA YENIDEN YAZILIYOR
 * Bir sonraki GERCEK parti geldiginde epoch yine farkli gorunuyor, sayac yine
 * sifirlaniyor ve o partinin TUM satirlari IKINCI KEZ faturaya yaziliyordu.
 */
test('bozuk/bos log partisi tekrar-eleme durumunu BOZMAZ (cifte faturalama)', async () => {
  const kayit = await (await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'OTOBUS-CIFT' })
  })).json()
  const H = { authorization: `Bearer ${kayit.device.token}` }
  const epoch = 'kurulum-abc'

  const satir = (seq) => JSON.stringify({
    seq, epoch, itemId: 'kahve-30', sha256: itemSha,
    startedAt: new Date().toISOString(), durationMs: 30000,
    completed: true, playlistVersion: 2, clockTrusted: true
  })

  // 1) Normal parti
  const ilk = await (await fetch(`${base}/api/v1/logs`, {
    method: 'POST', headers: H, body: [satir(1), satir(2)].join('\n')
  })).json()
  assert.equal(ilk.accepted, 2)
  assert.equal(ilk.ackSeq, 2)

  // 2) BOZUK parti: gecerli JSON satiri yok (ag/gzip sorunu gibi)
  const bozuk = await (await fetch(`${base}/api/v1/logs`, {
    method: 'POST', headers: H, body: 'bu-json-degil\n{bozuk\n'
  })).json()
  assert.equal(bozuk.accepted, 0)
  assert.equal(bozuk.ackSeq, 2, 'bilinen ackSeq korunmali (0 donmek cihazi geri sardirir)')
  assert.equal(bozuk.newInstall, false, 'bozuk parti YENI KURULUM sayilmamali')

  // 3) Cihaz ayni satirlari tekrar gonderdi (ACK'i alamadi sandi)
  const tekrar = await (await fetch(`${base}/api/v1/logs`, {
    method: 'POST', headers: H, body: [satir(1), satir(2)].join('\n')
  })).json()
  assert.equal(tekrar.accepted, 0, 'bozuk parti araya girse bile tekrar YAZILMAMALI')
  assert.equal(tekrar.ackSeq, 2)
})

/**
 * Elle atanan kanarya grubu manifestte GITMEK ZORUNDA.
 *
 * Sunucu bu degeri cihaz basina saklıyor ve panelde gosteriyordu ama manifestte hic
 * gondermiyordu; cihaz grubunu deviceId hash'inden hesapliyordu. Yani "su iki otobusu
 * kanarya yap" dendiginde guncelleme rastgele iki BASKA otobuse gidiyordu - kademeli
 * yayimin tum amaci (riski ALACAK cihazi secmek) ortadan kalkiyordu.
 */
/**
 * DAYPART YAZIM HATASI SESSIZ KALMAMALI.
 *
 * Cihaz tarafinda Daypart.matches, ayristirilamayan bir araligi bilincli olarak
 * "gun boyu gecerli" sayiyor: bozuk bir tanim yuzunden reklami hic oynatmamak, yanlis
 * saatte oynatmaktan pahali olurdu. Bedeli su: "7-10" gibi bir yazim hatasi kabul
 * edilir, isletmeci sabah kusagi satti sanir, reklam GUN BOYU doner ve kimse fark
 * etmez. Hatanin gorulebilecegi tek yer kayit ani.
 */
test('bozuk daypart REDDEDILIR (yoksa sessizce gun boyu donerdi)', async () => {
  const kur = (dayparts) => fetch(`${base}/api/admin/campaign`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({
      id: 'daypart-testi', itemSha, title: 'test',
      validUntil: new Date(Date.now() + 86400000).toISOString(), dayparts
    })
  })

  for (const bozuk of [['7-10'], ['25:00-30:00'], ['07:00'], ['07:00-10:00', 'oglen'], ['0700-1000']]) {
    const r = await kur(bozuk)
    assert.equal(r.status, 400, `"${bozuk}" reddedilmeliydi`)
    assert.match((await r.json()).error, /daypart/)
  }

  // Baslangic = bitis anlamsiz: "gun boyu" istiyorsa alan BOS birakilmali
  const esit = await kur(['09:00-09:00'])
  assert.equal(esit.status, 400)

  // Gecerli olanlar kabul edilmeli - gece yarisini asan aralik dahil
  for (const iyi of [[], ['07:00-10:00'], ['07:00-10:00', '17:00-20:00'], ['22:00-02:00'], ['00:00-23:59']]) {
    const r = await kur(iyi)
    assert.equal(r.status, 200, `"${iyi}" kabul edilmeliydi`)
    assert.deepEqual((await r.json()).campaign.dayparts, iyi)
  }

  await fetch(`${base}/api/admin/campaign/daypart-testi`, { method: 'DELETE', headers: adminHeaders })
})

/**
 * Number(null) === 0 ve 0 "finite"dir: govdede rolloutGroup: null gonderen bir
 * istemci cihazi GRUP 0'a koyuyordu - her guncellemeyi ilk alan, istenmeyen kanarya.
 */
test('rolloutGroup null/bos gonderilirse KANARYA yapilmaz', async () => {
  for (const [deger, ad] of [[null, 'OTOBUS-N1'], ['', 'OTOBUS-N2'], [0, 'OTOBUS-N3'], [-1, 'OTOBUS-N4']]) {
    const r = await (await fetch(`${base}/api/admin/device`, {
      method: 'POST',
      headers: { ...adminHeaders, 'content-type': 'application/json' },
      body: JSON.stringify({ id: ad, rolloutGroup: deger })
    })).json()
    assert.equal(r.device.rolloutPinned, false, `${JSON.stringify(deger)} elle atama sayilmamali`)
    assert.ok(r.device.rolloutGroup >= 1, `grup 0 olmamali (${ad}: ${r.device.rolloutGroup})`)
  }

  // Gecerli bir deger ise ELLE ATAMA sayilir
  const k = await (await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'OTOBUS-PIN', rolloutGroup: 1 })
  })).json()
  assert.equal(k.device.rolloutPinned, true)
  assert.equal(k.device.rolloutGroup, 1)
})

test('cihaza elle atanan rollout grubu IMZALI manifestte gonderilir', async () => {
  const kayit = await (await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'OTOBUS-KANARYA', rolloutGroup: 1 })
  })).json()
  assert.equal(kayit.device.rolloutGroup, 1)

  const r = await fetch(`${base}/api/v1/manifest`, {
    headers: { authorization: `Bearer ${kayit.device.token}` }
  })
  const m = verifyEnvelope(await r.json())
  assert.ok(m, 'imza dogrulanmali')
  assert.equal(m.deviceRolloutGroup, 1, 'elle atanan grup manifestte olmali')
})

test('KRITIK: fabrika ayarindan sonra loglar sessizce atilmaz (epoch)', async () => {
  // Bu testin korudugu hata: cihaz fabrika ayarina donunce seq 1'den baslar.
  // Sadece seq'e bakan bir tekrar elemesi TUM yeni loglari atar, ustelik yuksek
  // ackSeq dondurdugu icin cihaz onlari silerdi -> fatura verisi kalici kaybolur.
  const kayit = await (await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'OTOBUS-099' })
  })).json()
  const H = { authorization: `Bearer ${kayit.device.token}` }

  const yolla = (seq, epoch) => fetch(`${base}/api/v1/logs`, {
    method: 'POST', headers: H,
    body: JSON.stringify({
      seq, epoch, itemId: 'kahve-30', sha256: itemSha,
      startedAt: new Date().toISOString(), durationMs: 30000,
      completed: true, playlistVersion: 2, clockTrusted: true
    })
  }).then((r) => r.json())

  // Normal calisma
  const a = await yolla(5, 'kurulum-A')
  assert.equal(a.accepted, 1)
  assert.equal(a.ackSeq, 5)

  // Ayni kurulumda eski seq tekrar gelirse: elenmeli (tekrar elemesi hala calisiyor)
  const b = await yolla(3, 'kurulum-A')
  assert.equal(b.accepted, 0, 'ayni epoch icinde eski seq elenmeli')

  // FABRIKA AYARI: yeni epoch, seq 1'den basliyor -> KABUL EDILMELI
  const c = await yolla(1, 'kurulum-B')
  assert.equal(c.newInstall, true, 'yeni kurulum tespit edilmeli')
  assert.equal(c.accepted, 1, 'fabrika ayarindan sonraki log KABUL EDILMELI')
  assert.equal(c.ackSeq, 1, 'ack yeni sayaca gore donmeli, eski yuksek deger degil')

  // Yeni kurulum icinde tekrar elemesi yeniden calisiyor
  const d = await yolla(1, 'kurulum-B')
  assert.equal(d.accepted, 0)
  const e = await yolla(2, 'kurulum-B')
  assert.equal(e.accepted, 1)
})

test('epoch gondermeyen eski cihazlar calismaya devam eder', async () => {
  const kayit = await (await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'OTOBUS-098' })
  })).json()
  const H = { authorization: `Bearer ${kayit.device.token}` }
  const yolla = (seq) => fetch(`${base}/api/v1/logs`, {
    method: 'POST', headers: H,
    body: JSON.stringify({ seq, itemId: 'kahve-30', sha256: itemSha,
      startedAt: new Date().toISOString(), durationMs: 1000, completed: true,
      playlistVersion: 2, clockTrusted: true })
  }).then((r) => r.json())

  assert.equal((await yolla(1)).accepted, 1)
  assert.equal((await yolla(1)).accepted, 0, 'epochsuz cihazda da tekrar elenmeli')
  assert.equal((await yolla(2)).accepted, 1)
})

test('heartbeat panele islenir', async () => {
  const r = await fetch(`${base}/api/v1/heartbeat`, {
    method: 'POST',
    headers: { authorization: `Bearer ${deviceToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ appVersion: 41, playlistVersion: 2, readyItems: 1, totalItems: 1, freeBytes: 8e9, rssi: -58, clockTrusted: true, reboots: 0 })
  })
  assert.equal(r.status, 200)

  const state = await (await fetch(`${base}/api/admin/state`, { headers: adminHeaders })).json()
  const d = state.devices.find((x) => x.id === 'OTOBUS-014')
  assert.equal(d.stale, false)
  assert.equal(d.readyItems, 1)
  assert.equal(d.rssi, -58)
})

test('pencere raporu: 3 dakika yetiyor mu sorusunu olcer', async () => {
  // Bu rapor projenin temel tasarim sorusunun cevabi. Heartbeat gecmisi
  // tutulmasaydi her heartbeat bir oncekini ezer ve soru CEVAPSIZ kalirdi.
  const at = (bayt) => fetch(`${base}/api/v1/heartbeat`, {
    method: 'POST',
    headers: { authorization: `Bearer ${deviceToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ sessionBytes: bayt, readyItems: 2, totalItems: 3, playlistVersion: 2 })
  })

  await at(3_000_000)
  await at(11_000_000)
  await at(7_000_000)

  const csv = await (await fetch(`${base}/api/admin/pencere-raporu.csv`, { headers: adminHeaders })).text()
  assert.match(csv, /otobus;gun;senkron_sayisi;toplam_MB;ortalama_pencere_MB;en_buyuk_pencere_MB/)

  const satir = csv.split('\n').find((l) => l.startsWith('OTOBUS-014'))
  assert.ok(satir, 'cihaz satiri raporda olmali')
  const [, , senkron, toplam, ortalama, enBuyuk] = satir.split(';')
  assert.equal(Number(senkron) >= 3, true, `en az 3 senkron beklenir, ${senkron} geldi`)
  assert.equal(Number(toplam) >= 21, true, `toplam >= 21 MB beklenir, ${toplam} geldi`)
  assert.equal(Number(enBuyuk), 11, `en buyuk pencere 11 MB olmali, ${enBuyuk} geldi`)
  assert.ok(Number(ortalama) > 0)
})

test('EKRAN BOS ve guvenli mod panele ulasir', async () => {
  // Bu sistemin en kotu sonucu ekranin siyah kalmasidir; panelin bunu
  // gorebilmesi icin sinyalin uctan uca aktigini dogruluyoruz.
  const r = await fetch(`${base}/api/v1/heartbeat`, {
    method: 'POST',
    headers: { authorization: `Bearer ${deviceToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      appVersion: 41, playlistVersion: 2, readyItems: 0, totalItems: 3,
      playableItems: 0, safeMode: true, clockTrusted: false,
      lastError: 'EKRAN BOS: oynatilacak icerik yok'
    })
  })
  assert.equal(r.status, 200)

  const state = await (await fetch(`${base}/api/admin/state`, { headers: adminHeaders })).json()
  const d = state.devices.find((x) => x.id === 'OTOBUS-014')
  assert.equal(d.playableItems, 0, 'panel ekranin bos oldugunu gorebilmeli')
  assert.equal(d.safeMode, true, 'panel guvenli modu gorebilmeli')
  assert.match(d.lastError, /EKRAN BOS/)
})

test('oynatma kaniti raporu CSV uretir', async () => {
  const csv = await (await fetch(`${base}/api/admin/report.csv`, { headers: adminHeaders })).text()
  assert.match(csv, /kampanya;reklamveren;otobus;gun;oynatma_sayisi/)
  assert.match(csv, /kahve-30;Kahve A\.S\.;OTOBUS-014;.*;3;90;0/)
})

test('temizlik kullanilmayan icerigi bildirir ve siler', async () => {
  // Kullanilmayan ikinci bir icerik yukle (hicbir kampanyada gecmeyecek)
  const atil = crypto.randomBytes(128 * 1024)
  const up = await fetch(`${base}/api/admin/upload?name=kullanilmayan.mp4`, {
    method: 'POST', headers: adminHeaders, body: atil
  })
  const { item } = await up.json()
  const dosya = path.join(DATA, 'content', `${item.sha256}.mp4`)
  assert.ok(fs.existsSync(dosya))

  // Once KURU PROVA: hicbir sey silinmemeli
  const prova = await (await fetch(`${base}/api/admin/temizlik`, {
    method: 'POST', headers: { ...adminHeaders, 'content-type': 'application/json' }, body: '{}'
  })).json()
  assert.equal(prova.kuruProva, true)
  assert.equal(prova.silinen, 1)
  assert.equal(prova.icerikler[0].sha256, item.sha256)
  assert.ok(fs.existsSync(dosya), 'kuru provada dosya SILINMEMELI')

  // Simdi gercekten sil
  const sonuc = await (await fetch(`${base}/api/admin/temizlik`, {
    method: 'POST', headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ uygula: true })
  })).json()
  assert.equal(sonuc.kuruProva, false)
  assert.equal(sonuc.silinen, 1)
  assert.ok(!fs.existsSync(dosya), 'kullanilmayan dosya silinmis olmali')

  // KULLANILAN icerik korunmali
  assert.ok(fs.existsSync(path.join(DATA, 'content', `${itemSha}.mp4`)), 'kampanyadaki icerik KORUNMALI')
})

test('iptal edilen cihaz 403 alir', async () => {
  await fetch(`${base}/api/admin/device`, {
    method: 'POST',
    headers: { ...adminHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'OTOBUS-014', revoked: true })
  })
  const r = await fetch(`${base}/api/v1/manifest`, { headers: { authorization: `Bearer ${deviceToken}` } })
  assert.equal(r.status, 403)
})
