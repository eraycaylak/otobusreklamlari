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
      headers: { range: `bytes=${c.offset}-${c.offset + c.len - 1}` }
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
      headers: { range: `bytes=${c.offset}-${c.offset + c.len - 1}` }
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
  assert.deepEqual(await r1.json(), { ackSeq: 3, accepted: 3 })

  // Cihaz ACK'i alamadi ve ayni paketi tekrar gonderdi -> tekrar yazilmamali
  const r2 = await fetch(`${base}/api/v1/logs`, {
    method: 'POST',
    headers: { authorization: `Bearer ${deviceToken}`, 'content-encoding': 'gzip' },
    body
  })
  assert.deepEqual(await r2.json(), { ackSeq: 3, accepted: 0 })
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

test('oynatma kaniti raporu CSV uretir', async () => {
  const csv = await (await fetch(`${base}/api/admin/report.csv`, { headers: adminHeaders })).text()
  assert.match(csv, /kampanya;reklamveren;otobus;gun;oynatma_sayisi/)
  assert.match(csv, /kahve-30;Kahve A\.S\.;OTOBUS-014;.*;3;90;0/)
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
