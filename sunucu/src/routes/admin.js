import express from 'express'
import fs from 'node:fs'
import path from 'node:path'
import { config, paths } from '../config.js'
import { db, save, bumpPlaylistVersion, readPlayLogs, readHeartbeats } from '../store.js'
import { ingest } from '../transcode.js'
import { buildChunks } from '../chunker.js'
import { sha256File, randomToken, safeEqual, publicKeyBase64 } from '../crypto.js'
import { tooManyFailures, noteFailure, noteSuccess, validId, csvSafe } from '../guard.js'
import { pipeline } from 'node:stream/promises'

/**
 * Yuklemeyi dogrudan diske akitir.
 * Video/APK yuzlerce MB olabilir; express.raw bunlari RAM'e alir ve sunucuyu oldurur.
 */
const MAX_UPLOAD_BYTES = Number(process.env.MAX_UPLOAD_BYTES || 2 * 1024 * 1024 * 1024)

async function streamToFile (req, filePath) {
  // Disk tukenmesine karsi sert sinir: sinir asilinca akis kesilir ve
  // yarim dosya silinir. Sinirsiz birakmak tek bir istekle sunucuyu doldurabilirdi.
  let written = 0
  let aborted = null
  req.on('data', (chunk) => {
    written += chunk.length
    if (written > MAX_UPLOAD_BYTES && !aborted) {
      aborted = new Error(`yukleme cok buyuk (>${MAX_UPLOAD_BYTES} bayt)`)
      req.destroy(aborted)
    }
  })

  try {
    await pipeline(req, fs.createWriteStream(filePath))
  } catch (e) {
    fs.rmSync(filePath, { force: true })
    throw aborted || e
  }

  const size = fs.statSync(filePath).size
  if (size === 0) { fs.rmSync(filePath, { force: true }); throw new Error('govde bos') }
  return size
}

export const adminRouter = express.Router()

function auth (req, res, next) {
  const ip = req.ip || 'bilinmiyor'
  if (tooManyFailures(ip)) {
    return res.status(429).json({ error: 'cok fazla basarisiz deneme, 15 dakika bekleyin' })
  }
  const header = req.get('authorization') || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : (req.query.token || '')
  if (!safeEqual(token, config.adminToken)) {
    noteFailure(ip)
    return res.status(401).json({ error: 'yetkisiz' })
  }
  noteSuccess(ip)
  next()
}
adminRouter.use(auth)

/** Android uygulamasina gomulecek acik anahtar. */
adminRouter.get('/publickey', (req, res) => {
  res.json({ alg: 'ed25519', publicKeyBase64: publicKeyBase64() })
})

/** Panelin ihtiyac duydugu her sey tek istekte. */
adminRouter.get('/state', (req, res) => {
  const s = db()
  const beats = readHeartbeats()
  const now = Date.now()
  const maxStaleMs = 72 * 3600 * 1000

  const devices = Object.values(s.devices).map((d) => {
    const hb = beats[d.id]
    const lastSeen = hb ? new Date(hb.at).getTime() : 0
    return {
      id: d.id,
      label: d.label,
      group: d.group,
      rolloutGroup: d.rolloutGroup,
      revoked: !!d.revoked,
      lastSeenAt: hb ? hb.at : null,
      stale: !lastSeen || (now - lastSeen) > maxStaleMs,
      appVersion: hb?.appVersion ?? null,
      playlistVersion: hb?.playlistVersion ?? null,
      readyItems: hb?.readyItems ?? null,
      totalItems: hb?.totalItems ?? null,
      freeBytes: hb?.freeBytes ?? null,
      rssi: hb?.rssi ?? null,
      reboots: hb?.reboots ?? null,
      clockTrusted: hb?.clockTrusted ?? null,
      lastError: hb?.lastError ?? null
    }
  })

  res.json({
    playlistVersion: s.playlistVersion,
    app: s.app,
    devices,
    items: Object.values(s.items).map(({ chunks, ...rest }) => ({ ...rest, chunkCount: chunks.length })),
    campaigns: Object.values(s.campaigns)
  })
})

/**
 * Ham video yukleme. Govde dogrudan dosya (multipart yok - bagimlilik azaltildi).
 *   curl -H "Authorization: Bearer <token>" --data-binary @reklam.mov \
 *        "http://sunucu/api/admin/upload?name=reklam.mov"
 */
adminRouter.post('/upload', async (req, res) => {
    const name = String(req.query.name || 'yukleme.mp4')
    const safe = name.replace(/[^\w.\-]/g, '_')
    const incoming = path.join(paths.incoming, `${Date.now()}-${safe}`)
    try {
      await streamToFile(req, incoming)
      const item = await ingest(incoming, name)
      res.json({ ok: true, item: { ...item, chunks: undefined, chunkCount: item.chunks.length } })
    } catch (e) {
      fs.rmSync(incoming, { force: true })
      res.status(500).json({ error: 'yukleme/transcode basarisiz', detail: String(e.message || e) })
    }
  }
)

/** Kampanya olustur/guncelle. Yayin listesi bundan uretilir. */
adminRouter.post('/campaign', express.json(), (req, res) => {
  const b = req.body || {}
  if (!b.itemSha) return res.status(400).json({ error: 'itemSha zorunlu' })

  const s = db()
  if (!s.items[b.itemSha]) return res.status(400).json({ error: 'icerik bulunamadi' })

  const id = b.id || `k-${Date.now().toString(36)}`
  // Bu deger hem nesne anahtari hem rapor sutunu oluyor; serbest birakilmamali.
  if (!validId(id)) {
    return res.status(400).json({ error: 'gecersiz id', detail: 'izin verilenler: A-Z a-z 0-9 . _ : - (en fazla 64)' })
  }
  const evergreen = !!b.evergreen

  // Evergreen olmayan bir kampanyanin bitis tarihi ZORUNLU.
  // 4G yok -> uzaktan "kaldir" komutu yok. Bitis tarihi icerige gomulu degilse
  // suresi bitmis ucretli reklam otobuste donmeye devam eder. Bu ticari/hukuki risk.
  if (!evergreen && !b.validUntil) {
    return res.status(400).json({
      error: 'validUntil zorunlu',
      detail: 'Uzaktan anlik kaldirma kanali yok. Evergreen olmayan her kampanyanin bitis tarihi olmali.'
    })
  }

  s.campaigns[id] = {
    id,
    itemSha: b.itemSha,
    title: b.title || id,
    advertiser: b.advertiser || '',
    validFrom: evergreen ? null : (b.validFrom || null),
    validUntil: evergreen ? null : b.validUntil,
    dayparts: Array.isArray(b.dayparts) ? b.dayparts : [],
    weight: Math.max(1, Number(b.weight) || 1),
    groups: Array.isArray(b.groups) ? b.groups : [],
    evergreen,
    enabled: b.enabled !== false,
    updatedAt: new Date().toISOString()
  }
  save()
  const v = bumpPlaylistVersion()
  res.json({ ok: true, campaign: s.campaigns[id], playlistVersion: v })
})

adminRouter.delete('/campaign/:id', (req, res) => {
  const s = db()
  if (!s.campaigns[req.params.id]) return res.status(404).json({ error: 'yok' })
  delete s.campaigns[req.params.id]
  save()
  res.json({ ok: true, playlistVersion: bumpPlaylistVersion() })
})

/** Cihaz kaydi. Donen token provizyonda cihaza yazilir; bir daha gosterilmez. */
adminRouter.post('/device', express.json(), (req, res) => {
  const b = req.body || {}
  if (!b.id) return res.status(400).json({ error: 'id zorunlu (ornek: OTOBUS-014)' })
  // Cihaz id'si heartbeat ve kanit karesi DOSYA ADI olarak kullaniliyor.
  if (!validId(b.id)) {
    return res.status(400).json({ error: 'gecersiz cihaz id', detail: 'izin verilenler: A-Z a-z 0-9 . _ : - (en fazla 64)' })
  }

  const s = db()
  const existing = s.devices[b.id]
  const token = b.regenerateToken || !existing ? randomToken() : existing.token

  s.devices[b.id] = {
    id: b.id,
    token,
    label: b.label || b.id,
    group: b.group || 'default',
    // Kademeli yayim grubu: uygulama guncellemesi once kucuk gruba gider.
    rolloutGroup: Number.isFinite(Number(b.rolloutGroup))
      ? Number(b.rolloutGroup)
      : (existing?.rolloutGroup ?? rolloutGroupOf(b.id, config.rolloutGroups)),
    note: b.note || '',
    revoked: !!b.revoked,
    createdAt: existing?.createdAt || new Date().toISOString()
  }
  save()
  res.json({ ok: true, device: s.devices[b.id] })
})

adminRouter.delete('/device/:id', (req, res) => {
  const s = db()
  if (!s.devices[req.params.id]) return res.status(404).json({ error: 'yok' })
  delete s.devices[req.params.id]
  save()
  res.json({ ok: true })
})

/**
 * Yeni APK yayimla.
 *   curl -H "Authorization: Bearer <t>" --data-binary @app.apk \
 *     "http://sunucu/api/admin/app?versionCode=42&versionName=1.4.0&rolloutGroup=1"
 *
 * rolloutGroup: cihazin grubu bu degerden KUCUK/ESITSE guncellemeyi alir.
 * Once 1 ver (birkac cihaz), 48 saat sorun yoksa rolloutGroups degerine cikar.
 */
adminRouter.post('/app', async (req, res) => {
    // Tam sayi olmali: dosya adinda kullaniliyor ve Android versionCode zaten tam sayidir.
    const versionCode = Number(req.query.versionCode)
    if (!Number.isInteger(versionCode) || versionCode < 1 || versionCode > 2_000_000_000) {
      return res.status(400).json({ error: 'versionCode 1..2000000000 arasi tam sayi olmali' })
    }

    const name = `reklam-${versionCode}.apk`
    const file = path.join(paths.app, name)
    try { await streamToFile(req, file) } catch (e) { return res.status(400).json({ error: String(e.message || e) }) }

    const sha = await sha256File(file)
    const { size, chunks } = await buildChunks(file)

    const s = db()
    s.app = {
      versionCode,
      versionName: String(req.query.versionName || versionCode),
      file: `app/${name}`,
      size,
      sha256: sha,
      chunks,
      rolloutGroup: Number(req.query.rolloutGroup || 1),
      critical: req.query.critical === '1',
      uploadedAt: new Date().toISOString()
    }
    save()
    bumpPlaylistVersion()
    res.json({ ok: true, app: { ...s.app, chunks: undefined, chunkCount: chunks.length } })
  }
)

/** Kademeli yayimi genislet: rolloutGroup degerini yukselt. */
adminRouter.post('/app/rollout', express.json(), (req, res) => {
  const s = db()
  if (!s.app) return res.status(400).json({ error: 'yayimlanmis apk yok' })
  s.app.rolloutGroup = Number(req.body?.rolloutGroup ?? config.rolloutGroups)
  save()
  res.json({ ok: true, app: { ...s.app, chunks: undefined } })
})

/**
 * Oynatma kaniti raporu (reklamveren faturasi icin).
 * Sadece TAMAMLANMIS oynatmalar sayilir; yarim kalan oynatma faturalanamaz.
 */
adminRouter.get('/report.csv', (req, res) => {
  const from = req.query.from ? String(req.query.from).slice(0, 10) : null
  const to = req.query.to ? String(req.query.to).slice(0, 10) : null
  const campaign = req.query.campaign ? String(req.query.campaign) : null

  const rows = readPlayLogs(from, to).filter((r) =>
    r.completed && (!campaign || r.itemId === campaign)
  )

  const agg = new Map()
  for (const r of rows) {
    const key = `${r.itemId}\u0000${r.deviceId}\u0000${(r.startedAt || '').slice(0, 10)}`
    const cur = agg.get(key) || { itemId: r.itemId, deviceId: r.deviceId, day: (r.startedAt || '').slice(0, 10), plays: 0, totalMs: 0, untrustedClock: 0 }
    cur.plays += 1
    cur.totalMs += Number(r.durationMs) || 0
    if (r.clockTrusted === false) cur.untrustedClock += 1
    agg.set(key, cur)
  }

  const s = db()
  const lines = ['kampanya;reklamveren;otobus;gun;oynatma_sayisi;toplam_saniye;saati_supheli_kayit']
  for (const a of [...agg.values()].sort((x, y) => (x.day + x.itemId).localeCompare(y.day + y.itemId))) {
    const c = s.campaigns[a.itemId]
    lines.push([
      csvSafe(a.itemId),
      csvSafe(c?.advertiser || ''),
      csvSafe(a.deviceId),
      a.day,
      a.plays,
      Math.round(a.totalMs / 1000),
      a.untrustedClock
    ].join(';'))
  }

  res.set('Content-Type', 'text/csv; charset=utf-8')
  res.set('Content-Disposition', 'attachment; filename="oynatma-raporu.csv"')
  res.send('﻿' + lines.join('\n'))
})

/**
 * Kademeli yayim grubu hash'i.
 *
 * DIKKAT: Bu formul CIHAZDAKININ BIREBIR AYNISI olmak zorunda
 * (android .../sync/RolloutGroup.kt). Farklilasirsa sunucu cihazi 2. grupta
 * sanirken cihaz kendini 3. grupta sanir ve kademeli yayim ongorulemez olur.
 * Iki tarafin ayni sonucu urettigi testle dogrulanmistir.
 */
function rolloutGroupOf (deviceId, groups) {
  if (groups <= 1) return 1
  let h = 0
  for (let i = 0; i < deviceId.length; i++) h = (Math.imul(31, h) + deviceId.charCodeAt(i)) | 0
  // Math.abs(-2147483648) === 2147483648 (32-bit tasma); Kotlin'de abs ayni degeri
  // negatif dondurur. Iki tarafin ayni davranmasi icin bu durum ozel ele aliniyor.
  const positive = h === -2147483648 ? 0 : Math.abs(h)
  return 1 + (positive % groups)
}
