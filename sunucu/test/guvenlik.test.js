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

test('cok fazla basarisiz admin denemesi IP kilitler', async () => {
  // Bu test EN SONDA: kilitlenen IP sonraki testleri etkilerdi.
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
