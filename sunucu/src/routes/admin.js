import express from 'express'
import fs from 'node:fs'
import path from 'node:path'
import { config, paths } from '../config.js'
import { db, save, bumpPlaylistVersion, readPlayLogs, readHeartbeats, readHeartbeatHistory } from '../store.js'
import { ingest } from '../transcode.js'
import { buildChunks } from '../chunker.js'
import { sha256File, randomToken, safeEqual, publicKeyBase64 } from '../crypto.js'
import { tooManyFailures, noteFailure, noteSuccess, validId, csvSafe, tut } from '../guard.js'
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
  // Kapsam 'admin': dar esik. Cihaz sayacindan AYRI - onceden ayni sayaci
  // paylasiyorlardi ve bozuk tokenli bir otobus paneli de kilitliyordu.
  const ip = req.ip || 'bilinmiyor'
  if (tooManyFailures('admin', ip)) {
    return res.status(429).json({ error: 'cok fazla basarisiz deneme, 15 dakika bekleyin' })
  }
  const header = req.get('authorization') || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : (req.query.token || '')
  if (!safeEqual(token, config.adminToken)) {
    noteFailure('admin', ip)
    return res.status(401).json({ error: 'yetkisiz' })
  }
  noteSuccess('admin', ip)
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
      // Elle mi atandi (kanarya) yoksa cihaz id hash'inden mi geldi?
      rolloutPinned: !!d.rolloutPinned,
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
      // 0 oynatilabilir oge = EKRAN BOS. Panelin en yuksek oncelikli alarmi budur.
      playableItems: hb?.playableItems ?? null,
      safeMode: hb?.safeMode ?? false,
      // Oynatilamayan icerik: dosya saglam indi ama cihaz cozemiyor -> yeniden kodlanmali
      badItems: hb?.badItems ?? null,
      // Son pencerede inen bayt: "3 dakika yetiyor mu?" sorusunun dogrudan cevabi
      sessionBytes: hb?.sessionBytes ?? null,
      pendingLogs: hb?.pendingLogs ?? null,
      model: hb?.model ?? null,
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
adminRouter.post('/upload', tut(async (req, res) => {
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
  })
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

  /*
   * DAYPART BICIMI DOGRULANIYOR - HATA BURADA GORUNMELI.
   *
   * Cihaz tarafinda Daypart.matches, AYRISTIRILAMAYAN bir araligi bilincli olarak
   * "gun boyu gecerli" sayiyor: bozuk bir tanim yuzunden reklami hic oynatmamak,
   * yanlis saatte oynatmaktan daha pahali olurdu. Ama bunun bedeli sudur: panelde
   * "7-10" veya "25:00-30:00" gibi bir yazim hatasi SESSIZCE kabul edilir,
   * isletmeci sabah kusagi satti sanir, reklam GUN BOYU doner ve kimse fark etmez.
   *
   * Hatanin gorulebilecegi tek yer burasi: operatorun onunde, kayit anında.
   */
  const dayparts = Array.isArray(b.dayparts)
    ? b.dayparts.map((d) => String(d).trim()).filter(Boolean)
    : []
  const bozukDaypart = dayparts.find((d) => !/^([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d$/.test(d))
  if (bozukDaypart) {
    return res.status(400).json({
      error: 'gecersiz daypart',
      detail: `"${bozukDaypart}" - bicim SS:DD-SS:DD olmali (orn. 07:00-10:00). ` +
        'Gece yarisini asan aralik desteklenir: 22:00-02:00. ' +
        'Cihaz bozuk bir tanimi "gun boyu" sayar, yani hata sessizce gecerdi.'
    })
  }
  const esitUc = dayparts.find((d) => d.split('-')[0] === d.split('-')[1])
  if (esitUc) {
    return res.status(400).json({
      error: 'anlamsiz daypart',
      detail: `"${esitUc}" - baslangic ve bitis ayni. Gun boyu istiyorsaniz daypart'i BOS birakin.`
    })
  }

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
    dayparts,
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
    /*
     * KADEMELI YAYIM GRUBU.
     *
     * Number.isFinite(Number(...)) TEK BASINA YETMIYORDU: Number(null) ve Number('')
     * ikisi de 0 ve 0 "finite"dir. Yani govdede `rolloutGroup: null` gonderen bir
     * istemci cihazi GRUP 0'a koyuyordu - her guncellemeyi ilk alan, istenmeyen bir
     * kanarya. Artik POZITIF TAM SAYI sarti var.
     *
     * rolloutPinned: degerin ELLE mi atandigini ayirt ediyor. Otomatik atama cihaz
     * id hash'inden gelir ve cihazin kendi hesabiyla aynidir; panelde bu ikisini
     * ayirt etmek gerekiyor, yoksa "oto" ile "kanarya yaptim" ayni gorunur.
     */
    rolloutGroup: pozitifTam(b.rolloutGroup)
      ?? existing?.rolloutGroup
      ?? rolloutGroupOf(b.id, config.rolloutGroups),
    rolloutPinned: pozitifTam(b.rolloutGroup) != null ? true : (existing?.rolloutPinned ?? false),
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
adminRouter.post('/app', tut(async (req, res) => {
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
  })
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
 * Kullanilmayan icerigi sil.
 *
 * Cihaz tarafinda temizlik zaten var ama SUNUCUDA yoktu: hicbir kampanyada
 * gecmeyen yuklemeler diskte sonsuza kadar birikiyordu. Bir yil sonra
 * "sunucunun diski doldu" diye aranmamak icin bu ucu kullanin.
 *
 * Halen yayimda olan APK ve tum kampanyalarin icerigi KORUNUR.
 */
adminRouter.post('/temizlik', express.json(), (req, res) => {
  const s = db()
  const kullanilan = new Set(Object.values(s.campaigns).map((c) => c.itemSha))

  const silinecek = Object.values(s.items).filter((i) => !kullanilan.has(i.sha256))
  const kuruProva = req.body?.uygula !== true

  let bayt = 0
  const adlar = []
  for (const item of silinecek) {
    bayt += item.size
    adlar.push({ sha256: item.sha256, originalName: item.originalName, size: item.size })
    if (!kuruProva) {
      fs.rmSync(path.join(paths.content, `${item.sha256}.mp4`), { force: true })
      delete s.items[item.sha256]
    }
  }

  // Eski APK surumleri: sadece yayimdaki surum kalir
  const apkKorunan = s.app ? path.basename(s.app.file) : null
  const eskiApk = []
  for (const f of fs.readdirSync(paths.app)) {
    if (!f.endsWith('.apk') || f === apkKorunan) continue
    const tam = path.join(paths.app, f)
    const boyut = fs.statSync(tam).size
    eskiApk.push({ dosya: f, size: boyut })
    bayt += boyut
    if (!kuruProva) fs.rmSync(tam, { force: true })
  }

  // Kanit kareleri: cihaz basina gunde bir tane birikiyor.
  // 50 cihaz x 365 gun = yilda ~18.000 dosya. Saklama suresi disinda kalanlari sil.
  const kanitGun = Number(req.body?.kanitGun ?? 90)
  const kanitSiniri = Date.now() - kanitGun * 24 * 3600 * 1000
  let eskiKanit = 0
  for (const f of fs.readdirSync(paths.proof)) {
    const tam = path.join(paths.proof, f)
    const st = fs.statSync(tam)
    if (st.mtimeMs >= kanitSiniri) continue
    eskiKanit += 1
    bayt += st.size
    if (!kuruProva) fs.rmSync(tam, { force: true })
  }

  // Heartbeat gecmisi: gunluk bir dosya birikiyor. Oynatma loglari gibi faturaya
  // dayanak degil, olcum verisi - saklama suresi disinda kalanlar silinebilir.
  const gecmisGun = Number(req.body?.gecmisGun ?? 180)
  const gecmisSiniri = Date.now() - gecmisGun * 24 * 3600 * 1000
  let eskiGecmis = 0
  for (const f of fs.readdirSync(paths.heartbeat)) {
    if (!f.startsWith('gecmis-') || !f.endsWith('.ndjson')) continue
    const tam = path.join(paths.heartbeat, f)
    const st = fs.statSync(tam)
    if (st.mtimeMs >= gecmisSiniri) continue
    eskiGecmis += 1
    bayt += st.size
    if (!kuruProva) fs.rmSync(tam, { force: true })
  }

  if (!kuruProva) save()

  res.json({
    kuruProva,
    eskiHeartbeatGecmisi: eskiGecmis,
    silinen: adlar.length + eskiApk.length + eskiKanit + eskiGecmis,
    kazanilanBayt: bayt,
    icerikler: adlar,
    apkler: eskiApk,
    eskiKanitKaresi: eskiKanit,
    kanitSaklamaGun: kanitGun,
    not: kuruProva ? 'Gercekten silmek icin {"uygula":true} gonderin.' : 'Silindi.'
  })
})

/**
 * PENCERE RAPORU - projenin temel tasarim sorusunun cevabi.
 *
 * "Otobus noktada 3 dakika duruyor, yetiyor mu?" sorusu tum mimariyi belirledi
 * (transcode, onbellek kutusu, parcali indirme). Pilotta bunun GERCEKTEN boyle
 * oldugunu olcmeden buyutmeye gecmek tahmine dayanmak olur.
 *
 * Her satir: cihaz + gun -> kac senkron, toplam/ortalama inen bayt, en buyuk pencere.
 */
adminRouter.get('/pencere-raporu.csv', (req, res) => {
  const from = req.query.from ? String(req.query.from).slice(0, 10) : null
  const to = req.query.to ? String(req.query.to).slice(0, 10) : null

  const agg = new Map()
  for (const h of readHeartbeatHistory(from, to)) {
    const gun = String(h.at || '').slice(0, 10)
    const key = `${h.deviceId}\u0000${gun}`
    const cur = agg.get(key) || {
      deviceId: h.deviceId, gun, senkron: 0, toplamBayt: 0, enBuyuk: 0, hazir: null, toplamIcerik: null
    }
    cur.senkron += 1
    const bayt = Number(h.sessionBytes) || 0
    cur.toplamBayt += bayt
    if (bayt > cur.enBuyuk) cur.enBuyuk = bayt
    if (h.readyItems != null) cur.hazir = h.readyItems
    if (h.totalItems != null) cur.toplamIcerik = h.totalItems
    agg.set(key, cur)
  }

  const lines = ['otobus;gun;senkron_sayisi;toplam_MB;ortalama_pencere_MB;en_buyuk_pencere_MB;hazir_icerik']
  for (const a of [...agg.values()].sort((x, y) => (x.gun + x.deviceId).localeCompare(y.gun + y.deviceId))) {
    const mb = (b) => (b / 1e6).toFixed(2)
    lines.push([
      csvSafe(a.deviceId), a.gun, a.senkron,
      mb(a.toplamBayt), mb(a.senkron ? a.toplamBayt / a.senkron : 0), mb(a.enBuyuk),
      a.hazir != null ? `${a.hazir}/${a.toplamIcerik}` : ''
    ].join(';'))
  }

  res.set('Content-Type', 'text/csv; charset=utf-8')
  res.set('Content-Disposition', 'attachment; filename="pencere-raporu.csv"')
  res.send('\uFEFF' + lines.join('\n'))
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
/** Pozitif tam sayi mi? Degilse null. (Number(null)===0 tuzagi icin - bkz. /device) */
function pozitifTam (v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isInteger(n) && n > 0 ? n : null
}

function rolloutGroupOf (deviceId, groups) {
  if (groups <= 1) return 1
  let h = 0
  for (let i = 0; i < deviceId.length; i++) h = (Math.imul(31, h) + deviceId.charCodeAt(i)) | 0
  // Math.abs(-2147483648) === 2147483648 (32-bit tasma); Kotlin'de abs ayni degeri
  // negatif dondurur. Iki tarafin ayni davranmasi icin bu durum ozel ele aliniyor.
  const positive = h === -2147483648 ? 0 : Math.abs(h)
  return 1 + (positive % groups)
}
