import fs from 'node:fs'
import path from 'node:path'
import { paths, ensureDirs } from './config.js'

/**
 * Basit, bagimliliksiz kalici depo.
 *
 * NEDEN VERITABANI YOK: 50 cihazlik bir filo icin JSON + append-only log dosyasi
 * fazlasiyla yeterli ve hicbir native bagimlilik gerektirmiyor (kurulum sorunu cikmaz).
 * Olcek buyudugunde (yuzlerce cihaz / milyonlarca log satiri) SQLite'a gecin;
 * API yuzeyi ayni kalacak sekilde yazildi.
 *
 * Yazmalar atomiktir: gecici dosyaya yaz -> fsync -> rename.
 */

const EMPTY = {
  version: 1,
  devices: {},      // deviceId -> { id, token, group, rolloutGroup, label, note, createdAt, revoked }
  items: {},        // sha256 -> { sha256, file, size, durationMs, chunks[], originalName, createdAt }
  campaigns: {},    // campaignId -> { id, itemSha, title, advertiser, validFrom, validUntil, dayparts[], weight, groups[], evergreen, enabled }
  app: null,        // { versionCode, versionName, file, size, sha256, rolloutGroup, critical, uploadedAt }
  playlistVersion: 1,
  seenSeq: {}       // deviceId -> en yuksek ACK'lenen seq (tekrar eden log satirlarini eler)
}

let state = null

export function load () {
  ensureDirs()
  if (state) return state
  try {
    state = JSON.parse(fs.readFileSync(paths.db, 'utf8'))
    for (const [k, v] of Object.entries(EMPTY)) if (!(k in state)) state[k] = v
  } catch {
    state = structuredClone(EMPTY)
    save()
  }
  return state
}

export function save () {
  const tmp = paths.db + '.tmp'
  const fd = fs.openSync(tmp, 'w')
  fs.writeSync(fd, JSON.stringify(state, null, 2))
  fs.fsyncSync(fd)
  fs.closeSync(fd)
  fs.renameSync(tmp, paths.db)
}

export function db () {
  return load()
}

export function bumpPlaylistVersion () {
  const s = load()
  s.playlistVersion += 1
  save()
  return s.playlistVersion
}

/** Oynatma loglari gune gore append-only NDJSON dosyasinda tutulur. */
export function appendPlayLogs (deviceId, rows) {
  ensureDirs()
  const byDay = new Map()
  for (const r of rows) {
    const day = (r.startedAt || new Date().toISOString()).slice(0, 10)
    if (!byDay.has(day)) byDay.set(day, [])
    byDay.get(day).push({ ...r, deviceId, receivedAt: new Date().toISOString() })
  }
  for (const [day, list] of byDay) {
    const file = path.join(paths.logs, `${day}.ndjson`)
    fs.appendFileSync(file, list.map((x) => JSON.stringify(x)).join('\n') + '\n')
  }
}

export function readPlayLogs (fromDay, toDay) {
  ensureDirs()
  const out = []
  for (const f of fs.readdirSync(paths.logs).sort()) {
    if (!f.endsWith('.ndjson')) continue
    const day = f.slice(0, 10)
    if (fromDay && day < fromDay) continue
    if (toDay && day > toDay) continue
    for (const line of fs.readFileSync(path.join(paths.logs, f), 'utf8').split('\n')) {
      if (line.trim()) { try { out.push(JSON.parse(line)) } catch { /* bozuk satiri atla */ } }
    }
  }
  return out
}

export function writeHeartbeat (deviceId, data) {
  ensureDirs()
  const kayit = { ...data, deviceId, at: new Date().toISOString() }

  // Son durum (panel bunu okur)
  const file = path.join(paths.heartbeat, `${encodeURIComponent(deviceId)}.json`)
  fs.writeFileSync(file, JSON.stringify(kayit, null, 2))

  /*
   * GECMIS.
   *
   * Pilotun basari olcutu tam olarak bu veriye dayaniyor: "pencere basina kac bayt
   * indi, 3 dakika yetiyor mu?". Sadece son durumu saklamak bu soruyu CEVAPSIZ
   * birakiyordu - her heartbeat bir oncekini eziyordu. Gunluk append-only dosya
   * kucuk (cihaz basina gunde birkac satir) ve dogrudan analiz edilebilir.
   */
  const gun = kayit.at.slice(0, 10)
  fs.appendFileSync(path.join(paths.heartbeat, `gecmis-${gun}.ndjson`), JSON.stringify(kayit) + '\n')
}

/**
 * Heartbeat gecmisini oku (gun araligiyla).
 * Pilotun "3 dakikalik pencere yetiyor mu?" sorusunun veri kaynagi.
 */
export function readHeartbeatHistory (fromDay, toDay) {
  ensureDirs()
  const out = []
  for (const f of fs.readdirSync(paths.heartbeat).sort()) {
    if (!f.startsWith('gecmis-') || !f.endsWith('.ndjson')) continue
    const day = f.slice('gecmis-'.length, 'gecmis-'.length + 10)
    if (fromDay && day < fromDay) continue
    if (toDay && day > toDay) continue
    for (const line of fs.readFileSync(path.join(paths.heartbeat, f), 'utf8').split('\n')) {
      if (line.trim()) { try { out.push(JSON.parse(line)) } catch { /* bozuk satiri atla */ } }
    }
  }
  return out
}

export function readHeartbeats () {
  ensureDirs()
  const out = {}
  for (const f of fs.readdirSync(paths.heartbeat)) {
    if (!f.endsWith('.json')) continue   // gecmis-*.ndjson dosyalari haric
    try {
      const h = JSON.parse(fs.readFileSync(path.join(paths.heartbeat, f), 'utf8'))
      out[h.deviceId] = h
    } catch { /* bozuk dosyayi atla */ }
  }
  return out
}

/** Sadece testler icin: bellekteki durumu sifirla. */
export function _reset () {
  state = null
}
