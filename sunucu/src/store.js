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
 * Yazmalar atomiktir: gecici dosyaya yaz -> fsync -> rename -> dizini fsync.
 */

/*
 * BOS DURUM BIR FABRIKA, SABIT DEGIL.
 *
 * Daha once modul duzeyinde `const EMPTY = {...}` vardi ve eksik alan tamamlamasi
 * `state[k] = v` ile EMPTY'nin IC NESNESINI paylasiyordu. Sonuc: `seenSeq` alani
 * olmayan eski bir db.json ile acilista state.seenSeq === EMPTY.seenSeq oluyordu ve
 * cihazlarin ACK kayitlari modul sabitinin icinde birikiyordu. Bir sonraki bos
 * durum uretiminde o kayitlar "diriliyor", gercekten yeni loglar `seq > lastSeq`
 * testinden gecemiyor ama cihaza yuksek bir ackSeq donuyordu - yani cihaz
 * faturalanmamis satirlari silmis oluyordu. Fabrika bu aliasing'i imkansiz kilar.
 */
function bosDurum () {
  return {
    version: 1,
    devices: {},      // deviceId -> { id, token, group, rolloutGroup, label, note, createdAt, revoked }
    items: {},        // sha256 -> { sha256, file, size, durationMs, chunks[], originalName, createdAt }
    campaigns: {},    // campaignId -> { id, itemSha, title, advertiser, validFrom, validUntil, dayparts[], weight, groups[], evergreen, enabled }
    app: null,        // { versionCode, versionName, file, size, sha256, rolloutGroup, critical, uploadedAt }
    playlistVersion: 1,
    seenSeq: {}       // deviceId -> { epoch, seq } (tekrar eden log satirlarini eler)
  }
}

let state = null

/** Sunucu saatinden YYYY-AA-GG. Log/gecmis dosya adlarinin TEK kaynagi. */
export function sunucuGunu (d = new Date()) {
  return d.toISOString().slice(0, 10)
}

/**
 * Durumu diskten yukle.
 *
 * KRITIK: BOZUK BIR db.json ASLA EZILMEZ.
 *
 * Eski hali `catch { state = bos; save() }` idi ve dosya YOK / JSON BOZUK / EACCES
 * ayrimi yapmiyordu. Elektrik kesintisinde yarim kalan bir db.json ile servis
 * yeniden baslayinca 50 cihazin TOKEN'i, tum kampanyalar ve app kaydi bos duruma
 * sifirlanip diske YAZILIYORDU - elle kurtarma sansi da yok oluyordu. Sunucu
 * "saglikli" ayaga kalkiyor, /saglik 200 donuyor, ertesi sabah tum filo 401
 * aliyordu.
 *
 * Yeni davranis:
 *   ENOENT           -> gercekten ilk kurulum: bos durum, save() serbest
 *   digher her hata  -> bozuk dosyayi yana al, stderr'e bas ve FIRLAT (process durur)
 */
export function load () {
  ensureDirs()
  if (state) return state

  let ham
  try {
    ham = fs.readFileSync(paths.db, 'utf8')
  } catch (e) {
    if (e.code === 'ENOENT') {
      state = bosDurum()
      save()
      return state
    }
    // EACCES / EIO / EMFILE: veri ORADA olabilir, ustune yazmak kurtarilamaz kayiptir.
    throw new Error(
      `db.json okunamadi (${e.code || e.message}). Veri kaybi olmasin diye sunucu ` +
      `baslatilmiyor. Dosya izinlerini/diski kontrol edin: ${paths.db}`
    )
  }

  let cozulen
  try {
    cozulen = JSON.parse(ham)
    if (cozulen === null || typeof cozulen !== 'object' || Array.isArray(cozulen)) {
      throw new Error('kok deger bir nesne degil')
    }
  } catch (e) {
    const yedek = `${paths.db}.bozuk-${sunucuGunu()}-${process.pid}`
    try { fs.copyFileSync(paths.db, yedek) } catch { /* yedek alinamadi, yine de durduruyoruz */ }
    throw new Error(
      `db.json BOZUK (${e.message}). Kopyasi ${yedek} olarak saklandi ve UZERINE YAZILMADI. ` +
      `Elle onarin ya da bilincli olarak silin; sunucu bos bir veritabaniyla baslatilmayacak.`
    )
  }

  state = cozulen
  // Eksik alanlari tamamla - her birini KOPYALAYARAK (bkz. bosDurum yorumu).
  for (const [k, v] of Object.entries(bosDurum())) {
    if (!Object.hasOwn(state, k)) state[k] = v
  }
  return state
}

/**
 * Durumu atomik yaz.
 *
 * Uc ayri kusur onlendi:
 *  1. fd sizintisi: openSync ile closeSync arasinda try/finally yoktu. Disk dolunca
 *     her basarisiz yazma bir fd sizdiriyordu; save() her log yuklemesinde cagrildigi
 *     icin birkac yuz istekte fd limiti doluyor ve express.static bile dosya
 *     acamiyordu (sunucu ayakta, /saglik 200, hicbir cihaz indirmiyor).
 *  2. kismi yazma: writeSync'in donen bayt sayisi kontrol edilmiyordu; yarim yazilan
 *     tmp yine de db.json'un uzerine rename ediliyordu.
 *  3. dayaniksiz rename: dosya fsync ediliyor ama DIZIN edilmiyordu; ani guc
 *     kesintisinde rename kaybolabiliyordu.
 */
export function save () {
  const tmp = `${paths.db}.tmp`
  const veri = Buffer.from(JSON.stringify(state, null, 2), 'utf8')
  let fd
  try {
    fd = fs.openSync(tmp, 'w')
    let yazilan = 0
    while (yazilan < veri.length) {
      const n = fs.writeSync(fd, veri, yazilan, veri.length - yazilan, yazilan)
      if (n <= 0) throw new Error('yazma ilerlemedi (disk dolu olabilir)')
      yazilan += n
    }
    if (yazilan !== veri.length) {
      throw new Error(`kismi yazma: ${yazilan}/${veri.length} bayt`)
    }
    fs.fsyncSync(fd)
  } catch (e) {
    // Yarim tmp dosyasini BIRAKMA ve ASLA rename etme.
    try { if (fd !== undefined) fs.closeSync(fd); fd = undefined } catch { /* zaten kapali */ }
    try { fs.rmSync(tmp, { force: true }) } catch { /* silinemedi */ }
    throw e
  } finally {
    if (fd !== undefined) { try { fs.closeSync(fd) } catch { /* zaten kapali */ } }
  }
  fs.renameSync(tmp, paths.db)
  dizinFsync(paths.data)
}

/** rename'in dayanikli olmasi icin dizin girdisini de fsync et (Linux'ta sart). */
function dizinFsync (dizin) {
  let dfd
  try {
    dfd = fs.openSync(dizin, 'r')
    fs.fsyncSync(dfd)
  } catch { /* bazi dosya sistemlerinde dizin fsync desteklenmez - olumcul degil */ } finally {
    if (dfd !== undefined) { try { fs.closeSync(dfd) } catch { /* yoksay */ } }
  }
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

/**
 * Oynatma loglari gune gore append-only NDJSON dosyasinda tutulur.
 *
 * DOSYA ADI SUNUCU SAATINDEN TURETILIR - CIHAZIN SAATINDEN DEGIL.
 *
 * Eski hali `(r.startedAt || ...).slice(0,10)` idi. Iki ayri arizaya yol aciyordu:
 *  1. RTC pili olmayan bir stick 1970'te oynatma yapinca satirlar
 *     logs/1970-01-01.ndjson'a gidiyordu. readPlayLogs DOSYA ADINA gore filtreledigi
 *     icin o satirlar fatura raporunda HIC gorunmuyordu - "saati supheli" sayaci
 *     dahil, yani sistemin kendi uyari mekanizmasi tam gerektigi anda susuyordu.
 *  2. startedAt bir sayi ise `.slice` TypeError atiyor, "../" iceren bir string ise
 *     NDJSON logs dizininin DISINA yaziliyordu.
 *
 * Cihaz saati satir icinde `startedAt` olarak KORUNUR (fatura suresi oradan gelir),
 * gun gruplamasi ise `receivedAt` (sunucu saati) uzerinden yapilir.
 */
/**
 * Cihaz saatine ne kadar guvenilir?
 *
 * Bu tolerans faturanin GUN granulerligini korumak icindir: otobus geceyi
 * noktaya varmadan gecirirse ya da bir gun sahada kalirsa oynatmalari kendi
 * gunune yazilmali. 30 gunden uzak bir tarih ise artik bir "saat" degil bir
 * ariza isaretidir (RTC'siz stick 1970'te aciliyor) - o satir sunucu gunune
 * yazilir ve isaretlenir.
 */
const SAAT_TOLERANS_MS = 30 * 24 * 3600 * 1000

/**
 * FATURA GUNU BIR KEZ, SUNUCUDA, YAZMA ANINDA KARARLASTIRILIR.
 *
 * Dosya adi bu gunden turetilir; boylece rapor gun suzgeci DOSYA ADIYLA satirin
 * gunu arasinda ASLA ayrisamaz. Eski hali dosya adini dogrudan cihazin startedAt
 * degerinden aliyordu: RTC pili olmayan bir stick'in kayitlari
 * logs/1970-01-01.ndjson'a gidiyor ve rapor DOSYA ADINA gore suzdugu icin o
 * satirlar HICBIR fatura raporunda gorunmuyordu - "saati supheli" sayaci dahil,
 * yani sistemin kendi uyari mekanizmasi tam gerektigi anda susuyordu.
 */
export function faturaGunuHesapla (row, simdiMs) {
  const sunucuGun = new Date(simdiMs).toISOString().slice(0, 10)
  if (row.clockTrusted === false) return { gun: sunucuGun, kaynak: 'sunucu-saat-supheli' }
  const t = Date.parse(row.startedAt)
  if (!Number.isFinite(t)) return { gun: sunucuGun, kaynak: 'sunucu-tarih-yok' }
  if (Math.abs(t - simdiMs) > SAAT_TOLERANS_MS) return { gun: sunucuGun, kaynak: 'sunucu-tarih-uzak' }
  return { gun: new Date(t).toISOString().slice(0, 10), kaynak: 'cihaz' }
}

export function appendPlayLogs (deviceId, rows) {
  ensureDirs()
  if (!rows.length) return
  const simdiMs = Date.now()
  const simdi = new Date(simdiMs).toISOString()

  const guneGore = new Map()
  for (const r of rows) {
    const { gun, kaynak } = faturaGunuHesapla(r, simdiMs)
    if (!guneGore.has(gun)) guneGore.set(gun, [])
    guneGore.get(gun).push({ ...r, deviceId, receivedAt: simdi, faturaGunu: gun, gunKaynagi: kaynak })
  }

  for (const [gun, list] of guneGore) {
    // Gun daima sunucuda uretilen bir YYYY-AA-GG oldugu icin bu kontrol asla
    // dusmemeli; dustuyse logs dizini disina yazmaktansa patlamak dogrudur.
    if (!/^\d{4}-\d{2}-\d{2}$/.test(gun)) throw new Error(`gecersiz fatura gunu: ${gun}`)
    const file = path.join(paths.logs, `${gun}.ndjson`)
    if (!path.resolve(file).startsWith(path.resolve(paths.logs) + path.sep)) {
      throw new Error(`log yolu logs dizini disinda: ${file}`)
    }
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
