import express from 'express'
import zlib from 'node:zlib'
import fs from 'node:fs'
import path from 'node:path'
import { db, save, appendPlayLogs, writeHeartbeat } from '../store.js'
import { buildManifest } from '../manifest.js'
import { safeEqual } from '../crypto.js'
import { tooManyFailures, noteFailure, noteSuccess } from '../guard.js'
import { paths } from '../config.js'

export const deviceRouter = express.Router()

/** Bearer token ile cihaz kimligi. Iptal edilen cihaz 403 alir. */
function auth (req, res, next) {
  const ip = req.ip || 'bilinmiyor'
  if (tooManyFailures(ip)) {
    return res.status(429).json({ error: 'cok fazla basarisiz deneme' })
  }

  const header = req.get('authorization') || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : null
  if (!token) return res.status(401).json({ error: 'token yok' })

  const s = db()
  const device = Object.values(s.devices).find((d) => safeEqual(d.token, token))
  if (!device) {
    noteFailure(ip)
    return res.status(401).json({ error: 'token gecersiz' })
  }
  if (device.revoked) return res.status(403).json({ error: 'cihaz iptal edilmis' })

  noteSuccess(ip)
  req.device = device
  next()
}

/**
 * Cihazin pencerede cektigi ILK ve EN KUCUK istek.
 * Date basligi cihazin saatini duzeltmek icin kullaniliyor (ayri NTP portu gerekmez).
 */
deviceRouter.get('/manifest', auth, (req, res) => {
  const envelope = buildManifest(req.device)
  res.set('Cache-Control', 'no-store')
  res.set('Date', new Date().toUTCString())
  res.json(envelope)
})

/**
 * Oynatma loglari: gzip'lenmis NDJSON.
 * Tekrarlari (device, seq) ile eliyoruz - cihaz ACK almadan logu silmiyor,
 * bu yuzden ayni satir birden fazla kez gelebilir.
 */
deviceRouter.post('/logs', auth,
  express.raw({ type: () => true, limit: '32mb' }),
  (req, res) => {
    let body = req.body
    if (!Buffer.isBuffer(body) || body.length === 0) return res.status(400).json({ error: 'govde bos' })

    // body-parser, Content-Encoding: gzip gelen govdeyi KENDISI aciyor.
    // Bu yuzden basliga degil, gzip sihirli baytina (0x1f 0x8b) bakiyoruz:
    // boylece hem acilmis hem acilmamis govde dogru islenir.
    if (body.length > 2 && body[0] === 0x1f && body[1] === 0x8b) {
      try { body = zlib.gunzipSync(body) } catch { return res.status(400).json({ error: 'gzip cozulemedi' }) }
    }

    const s = db()
    const lastSeq = s.seenSeq[req.device.id] || 0
    let maxSeq = lastSeq
    const fresh = []

    for (const line of body.toString('utf8').split('\n')) {
      if (!line.trim()) continue
      let row
      try { row = JSON.parse(line) } catch { continue }
      const seq = Number(row.seq)
      if (!Number.isFinite(seq)) continue
      if (seq > lastSeq) fresh.push(row)
      if (seq > maxSeq) maxSeq = seq
    }

    if (fresh.length) appendPlayLogs(req.device.id, fresh)
    s.seenSeq[req.device.id] = maxSeq
    save()

    res.json({ ackSeq: maxSeq, accepted: fresh.length })
  }
)

/** Cihaz sagligi: surum, disk, sinyal, sicaklik, son hata, tamamlanma orani. */
deviceRouter.post('/heartbeat', auth, express.json({ limit: '256kb' }), (req, res) => {
  writeHeartbeat(req.device.id, {
    ...req.body,
    group: req.device.group,
    label: req.device.label,
    ip: req.ip
  })
  res.json({ ok: true, serverTime: new Date().toISOString() })
})

/**
 * "Kanit karesi": cihaz o an oynattigi videodan bir kare cikarip gonderir.
 *
 * DURUST NOT: Normal bir Android uygulamasi ekranin GERCEK goruntusunu sessizce
 * alamaz (MediaProjection kullanici onayi ister, CAPTURE_VIDEO_OUTPUT imza izni).
 * Bu yuzden ekran goruntusu degil, oynatilan dosyadan cikarilan kare gonderiliyor.
 * Ne kanitlar: o dosyanin o saatte oynatildigini. Ne kanitlamaz: TV'nin acik oldugunu.
 */
deviceRouter.post('/proof', auth,
  express.raw({ type: () => true, limit: '4mb' }),
  (req, res) => {
    if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
      return res.status(400).json({ error: 'govde bos' })
    }
    const stamp = new Date().toISOString().replace(/[:.]/g, '-')
    const file = path.join(paths.proof, `${encodeURIComponent(req.device.id)}_${stamp}.jpg`)
    fs.writeFileSync(file, req.body)
    res.json({ ok: true })
  }
)
