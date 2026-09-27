import express from 'express'
import zlib from 'node:zlib'
import fs from 'node:fs'
import path from 'node:path'
import { db, save, appendPlayLogs, writeHeartbeat } from '../store.js'
import { buildManifest } from '../manifest.js'
import { safeEqual } from '../crypto.js'
import { tooManyFailures, noteFailure, noteSuccess, tokenFingerprint } from '../guard.js'
import { paths } from '../config.js'

/**
 * Acilmis log govdesi icin ust sinir (sikistirma bombasina karsi).
 * Bir oynatma log satiri ~200 bayt; 16 MB, bir cihazin ACK beklerken
 * biriktirebilecegi her seyi fazlasiyla asar.
 */
const MAX_LOG_ACILMIS = Number(process.env.MAX_LOG_BYTES || 16 * 1024 * 1024)

export const deviceRouter = express.Router()

/**
 * Bearer token ile cihaz kimligi. Iptal edilen cihaz 403 alir.
 *
 * DENEME SINIRI IKI KATMANLI (bkz. guard.js):
 * Bu mimaride noktadaki TUM otobusler tek PtMP linkinin arkasinda, yani sunucuya
 * AYNI IP'den goruluyor. Tek bir IP sayaci, tokeni iptal edilmis bir otobusun o
 * noktadaki butun otobusleri kilitlemesine yol aciyordu. Artik asil sinir SUNULAN
 * TOKENIN parmak izine gore isliyor; IP sayaci yalnizca kaba kuvvete karsi, cok
 * yuksek bir esikle duruyor.
 */
export function auth (req, res, next) {
  const ip = req.ip || 'bilinmiyor'
  const header = req.get('authorization') || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : null
  const parmak = tokenFingerprint(token)

  if (tooManyFailures('device', parmak) || tooManyFailures('ip', ip)) {
    return res.status(429).json({ error: 'cok fazla basarisiz deneme' })
  }
  if (!token) return res.status(401).json({ error: 'token yok' })

  const s = db()
  const device = Object.values(s.devices).find((d) => safeEqual(d.token, token))
  if (!device) {
    noteFailure('device', parmak)
    noteFailure('ip', ip)
    return res.status(401).json({ error: 'token gecersiz' })
  }
  if (device.revoked) return res.status(403).json({ error: 'cihaz iptal edilmis' })

  /*
   * X-Cihaz BASLIGI TOKENIN SAHIBIYLE ESLESMEK ZORUNDA.
   *
   * Cihaz bu basligi, nokta onbellek kutusunun manifest onbellegini CIHAZ BASINA
   * bolumlemesi icin gonderiyor (anahtarda ham token yerine cihaz kimligi durur -
   * nginx onbellek anahtarini dosyanin basina duz metin yazar, yani token orada
   * birikirdi). Ama bolumleme anahtarini istemci verdigi icin dogrulanmadan
   * kabul edilemez: baska bir cihazin kimligini yazan gecerli bir token sahibi,
   * o cihazin onbellek girdisini KENDI manifestiyle zehirleyebilirdi. Kurban
   * cihaz manifesti reddeder (imza deviceId'yi kapsiyor) ve o pencerede senkron
   * olamaz.
   *
   * Uyusmazlikta 400 donuyoruz; nginx 400'u onbelleklemez, yani zehirlenecek bir
   * girdi hic olusmaz. Baslik YOKSA sorun degil (eski cihaz surumu): kutu o
   * durumda onbelleklemez, dogruluk bozulmaz.
   */
  const bildirilen = req.get('x-cihaz')
  if (bildirilen && bildirilen !== device.id) {
    noteFailure('device', parmak)
    return res.status(400).json({ error: 'X-Cihaz basligi token sahibiyle uyusmuyor' })
  }

  noteSuccess('device', parmak)
  noteSuccess('ip', ip)
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
      /*
       * SIKISTIRMA BOMBASI SINIRI - maxOutputLength ZORUNLU.
       *
       * Eskiden `zlib.gunzipSync(body)` sinirsizdi. 32 MB'lik govde sinirinin
       * ARKASINDAN gigabaytlarca veri acilabilir: gecerli bir cihaz tokeni olan
       * (sokulmus bir stick'ten cikarilmasi yeterli) biri tek istekle sunucunun
       * bellegini tuketir. Sunucu olurse o noktadaki TUM otobusler pencerelerini
       * kaybeder ve fatura loglari alinmaz - yani en pahali sonuc.
       *
       * Gercekci ust sinir: bir oynatma log satiri ~200 bayt, cihaz ACK alana kadar
       * biriktiriyor. 16 MB bir cihazin biriktirebilecegi her seyi fazlasiyla asar.
       */
      try {
        body = zlib.gunzipSync(body, { maxOutputLength: MAX_LOG_ACILMIS })
      } catch (e) {
        const bomba = String(e.message || '').includes('exceed')
        if (bomba) console.warn(`${req.device.id}: log govdesi ${MAX_LOG_ACILMIS} bayt sinirini asti (sikistirma bombasi?)`)
        return res.status(400).json({ error: bomba ? 'log govdesi cok buyuk' : 'gzip cozulemedi' })
      }
    }

    /*
     * SATIR SUZGECI - TEK BOZUK SATIR TUM PARTIYI VE ACK'I BLOKLAMAMALI.
     *
     * Onceden yalnizca `seq` dogrulaniyordu. `startedAt` hic dogrulanmadigi icin
     * store.appendPlayLogs onu dogrudan DOSYA YOLUNA koyuyordu:
     *   - startedAt bir SAYI ise (Entities.kt'de zaten `Long`; ISO'ya cevirme tek
     *     satirda yapiliyor ve kolayca kaybolur) `.slice is not a function` ->
     *     istek 500 -> cihaz ACK ALMADIGI icin satirlari SILMEZ -> her pencerede ayni
     *     partiyi yeniden yukler, yine 500 alir. O otobusun fatura verisi sunucuya
     *     HIC ulasmaz ve cihazin diski dolar.
     *   - startedAt "../../../tmp/x" ise NDJSON logs dizininin DISINA yazilirdi.
     *
     * Dosya adi artik sunucu saatinden turetiliyor (store.js), ama satir icindeki
     * deger de temiz olmali: rapor gun gruplamasinda kullaniliyor.
     */
    const ISO_BASI = /^\d{4}-\d{2}-\d{2}T/
    const rows = []
    let atilan = 0
    for (const line of body.toString('utf8').split('\n')) {
      if (!line.trim()) continue
      let row
      try { row = JSON.parse(line) } catch { atilan += 1; continue }
      if (row === null || typeof row !== 'object' || Array.isArray(row)) { atilan += 1; continue }
      if (!Number.isFinite(Number(row.seq))) { atilan += 1; continue }
      // startedAt normalize edilir: bicimi bozuksa satir ATILMAZ (fatura verisi
      // kaybolmaz), sadece alan temizlenir - rapor o satiri sunucu gunune yazar.
      if (typeof row.startedAt !== 'string' || !ISO_BASI.test(row.startedAt)) {
        const t = Date.parse(row.startedAt)
        row.startedAt = Number.isFinite(t) ? new Date(t).toISOString() : null
      }
      rows.push(row)
    }
    if (atilan) console.warn(`${req.device.id}: ${atilan} bozuk log satiri atlandi`)

    const s = db()

    /*
     * EPOCH - neden gerekli:
     *
     * seq cihazdaki veritabani satir kimliginden gelir ve 1'den baslar. Cihaz
     * fabrika ayarlarina donduruldugunde (ariza, yeniden provizyon) sayac yeniden
     * 1'den baslar. Sadece seq'e bakan bir tekrar elemesi, bu cihazdan gelen TUM
     * yeni loglari "zaten gordum" diye atardi - ustelik ackSeq olarak eski yuksek
     * degeri dondurdugu icin cihaz o satirlari "yuklendi" isaretleyip SILERDI.
     * Sonuc: o otobusun fatura verisi kalici olarak kaybolurdu.
     *
     * Cozum: cihaz, kurulum basina bir kez uretilen kalici bir epoch gonderiyor.
     * Fabrika ayari -> yeni epoch -> sayac temiz baslar. Ayni epoch icinde tekrar
     * eleme eskisi gibi calismaya devam eder.
     */
    const epoch = String(rows.find((r) => r.epoch)?.epoch || '')

    // Eski bicim (duz sayi) ile geriye donuk uyumluluk
    const kayit = s.seenSeq[req.device.id]
    const onceki = (kayit && typeof kayit === 'object')
      ? kayit
      : { epoch: '', seq: Number(kayit) || 0 }

    /*
     * AYRISTIRILABILIR SATIR YOKSA TEKRAR-ELEME DURUMUNA DOKUNMA.
     *
     * Onceden bos/bozuk bir parti (kirpilmis gzip, bir ara sunucunun ekledigi boslukla
     * bozulmus govde, eski surum) su zinciri tetikliyordu: rows bos -> epoch "" ->
     * kayitli epoch'tan farkli -> "yeni kurulum" -> lastSeq 0 -> ve en onemlisi
     * seenSeq YENIDEN '' EPOCH'LA YAZILIYORDU. Bir sonraki GERCEK parti geldiginde
     * epoch yine farkli gorunuyor, sayac yine sifirlaniyor ve o partinin TUM satirlari
     * IKINCI KEZ faturaya yaziliyordu. Yani gecici bir ag/gzip sorunu reklamverene
     * cifte faturalama olarak yansiyordu - sessizce.
     *
     * Ayristirilabilir satir yoksa yapacak bir sey de yok: kayitli durumu aynen koru
     * ve cihaza bilinen ackSeq'i dondur.
     */
    if (rows.length === 0) {
      return res.json({ ackSeq: onceki.seq, accepted: 0, newInstall: false })
    }

    /*
     * Epoch BOS gelirse de durumu bozmuyoruz: bos epoch "bilinmiyor" demektir
     * (eski cihaz surumu), "yeni kurulum" demek DEGILDIR. Yeni kurulum ancak
     * cihaz GERCEK bir epoch bildirdiginde ve o deger degistiginde soylenebilir.
     */
    const yeniKurulum = epoch !== '' && epoch !== onceki.epoch
    const lastSeq = yeniKurulum ? 0 : onceki.seq
    if (yeniKurulum) {
      console.log(`${req.device.id}: yeni kurulum tespit edildi (epoch "${onceki.epoch}" -> "${epoch}"), log sayaci sifirlandi`)
    }

    let maxSeq = lastSeq
    const fresh = []
    for (const row of rows) {
      const seq = Number(row.seq)
      if (seq > lastSeq) fresh.push(row)
      if (seq > maxSeq) maxSeq = seq
    }

    if (fresh.length) appendPlayLogs(req.device.id, fresh)
    // Bos epoch kayitli degeri EZMEZ: aksi halde bir sonraki gercek parti "yeni
    // kurulum" gibi gorunup ikinci kez faturaya girerdi.
    s.seenSeq[req.device.id] = { epoch: epoch || onceki.epoch, seq: maxSeq }
    save()

    res.json({ ackSeq: maxSeq, accepted: fresh.length, newInstall: yeniKurulum })
  }
)

/*
 * Cihaz sagligi: surum, disk, sinyal, sicaklik, son hata, tamamlanma orani.
 *
 * GOVDE BEYAZ LISTEDEN GECER - SERBEST JSON KALICIYA YAZILMAZ.
 *
 * Eski hali `{ ...req.body }` ile 256 KB'a kadar serbest JSON'u oldugu gibi kaydediyordu
 * ve writeHeartbeat her kaydi IKI yere yaziyor (son durum .json + gecmis .ndjson).
 * Bozulmus ya da kurcalanmis tek bir stick `lastError` alanina her heartbeat'te ~250 KB
 * yazarak gunde onlarca MB birikim uretebiliyordu; gecmis dosyalari 180 gun silinmiyor
 * ve /pencere-raporu.csv onlarin TAMAMINI RAM'e okuyor. Disk dolunca save() ve
 * appendPlayLogs yazamaz -> /logs 500 doner -> cihazlar ACK almadigi icin loglari
 * silmez ama sunucuda FATURA verisi hic olusmaz.
 */
const METIN_SINIRI = 500
function heartbeatAlanlari (b) {
  const ham = (b && typeof b === 'object' && !Array.isArray(b)) ? b : {}
  const sayi = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null }
  const metin = (v, n = METIN_SINIRI) => (v === undefined || v === null) ? null : String(v).slice(0, n)
  const bool = (v) => (v === undefined || v === null) ? null : !!v
  return {
    appVersion: sayi(ham.appVersion),
    appVersionName: metin(ham.appVersionName, 64),
    playlistVersion: sayi(ham.playlistVersion),
    readyItems: sayi(ham.readyItems),
    totalItems: sayi(ham.totalItems),
    playableItems: sayi(ham.playableItems),
    badItems: sayi(ham.badItems),
    pendingLogs: sayi(ham.pendingLogs),
    freeBytes: sayi(ham.freeBytes),
    sessionBytes: sayi(ham.sessionBytes),
    rssi: sayi(ham.rssi),
    temperature: sayi(ham.temperature),
    uptimeMs: sayi(ham.uptimeMs),
    reboots: sayi(ham.reboots),
    rebootsSince24h: sayi(ham.rebootsSince24h),
    clockTrusted: bool(ham.clockTrusted),
    clockNote: metin(ham.clockNote, 200),
    /*
     * LISTE NEDENI - "playableItems: 0" tek basina mudahaleye yol gostermiyor.
     * Cihaz tarafinda bu deger oynatma listesini KURAN kodun kendisinden geliyor,
     * yani panel gordugu sayiyla ekrandaki gercek arasinda ayrisma olamaz.
     */
    playlistReason: metin(ham.playlistReason, 200),
    safeMode: bool(ham.safeMode),
    deviceOwner: bool(ham.deviceOwner),
    policyErrors: metin(ham.policyErrors, 300),
    model: metin(ham.model, 80),
    timezone: metin(ham.timezone, 64),
    lastError: metin(ham.lastError),
    lastInstallError: metin(ham.lastInstallError),
    /*
     * SON ARIZALARIN LISTESI - beyaz listeden gecerek.
     *
     * lastError tek dizgiydi ve cihaz tarafinda SEKIZ yer ona yaziyordu: son yazan
     * digerlerini siliyordu. Disk dolu -> indirme hatasi -> log tasmasi zincirinde
     * operator ASIL SEBEBI hic gormuyordu.
     *
     * Govde cihazdan geldigi icin siki siniri var: en fazla 6 kayit, alanlar tek tek
     * okunuyor. (Serbest JSON'un kaliciya yazilmasi ayri bir ariza sinifiydi.)
     */
    errors: Array.isArray(ham.errors)
      ? ham.errors.slice(0, 6).map((e) => ({
          kod: metin(e && e.kod, 24) || 'genel',
          metin: metin(e && e.metin, 200) || '',
          yasSn: sayi(e && e.yasSn)
        }))
      : []
  }
}

deviceRouter.post('/heartbeat', auth, express.json({ limit: '256kb' }), (req, res) => {
  writeHeartbeat(req.device.id, {
    ...heartbeatAlanlari(req.body),
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
/*
 * KANIT KARESI SIKLIK SINIRI - SUNUCU TARAFINDA.
 *
 * "Gunde bir kare" kurali YALNIZCA cihazda uygulaniyordu (config.lastProofAt).
 * Cihaz guvenilmeyen taraftir: bozuk bir surum, kurcalanmis bir stick ya da
 * sifirlanmis bir SharedPreferences her pencerede kare gonderebilir. 4 MB x
 * cihaz sayisi x tekrar = disk dolar; dolu diskte save() ve appendPlayLogs
 * yazamaz, yani FATURA loglari da alinamaz. Sunucu kendi kuralini uygulamali.
 */
const PROOF_ARALIK_MS = Number(process.env.PROOF_MIN_ARALIK_MS || 6 * 3600 * 1000)
const sonKanit = new Map()   // deviceId -> epoch ms

deviceRouter.post('/proof', auth,
  express.raw({ type: () => true, limit: '4mb' }),
  (req, res) => {
    const once = sonKanit.get(req.device.id) || 0
    if (Date.now() - once < PROOF_ARALIK_MS) {
      return res.status(429).json({ error: 'kanit karesi cok sik', enErkenMs: PROOF_ARALIK_MS - (Date.now() - once) })
    }
    sonKanit.set(req.device.id, Date.now())
    if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
      return res.status(400).json({ error: 'govde bos' })
    }
    const stamp = new Date().toISOString().replace(/[:.]/g, '-')
    const file = path.join(paths.proof, `${encodeURIComponent(req.device.id)}_${stamp}.jpg`)
    fs.writeFileSync(file, req.body)
    res.json({ ok: true })
  }
)
