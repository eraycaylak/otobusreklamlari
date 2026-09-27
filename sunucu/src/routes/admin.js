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
  /*
   * SORGU DIZESINDEKI TOKEN KALDIRILDI.
   *
   * Tek kullanicisi panelin rapor indirme baglantisiydi; panel artik Authorization
   * basligi + blob indirme kullaniyor. Sorgu dizesinde tasinan bir admin tokeni
   * tarayici gecmisine, Referer basligina ve noktadaki onbellek kutusunun erisim
   * loguna giriyordu - sunucunun kendi log satirinda redakte etmemiz gerekmesi de
   * bunun kaniti. Baslik yolu her zaman vardi; ikinci yolu tutmanin faydasi yok.
   *
   * Elle curl kullananlar icin: -H "Authorization: Bearer <token>"
   */
  const header = req.get('authorization') || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : ''
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
  const maxStaleMs = config.staleHours * 3600 * 1000

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
      // Listenin NEDEN oyle oldugu: "0 oynatilabilir" tek basina ne yapilacagini
      // soylemiyor (inmemis icerik mi, suresi bitmis kampanyalar mi, saat mi?).
      playlistReason: hb?.playlistReason ?? null,
      // Son pencerede inen bayt: "3 dakika yetiyor mu?" sorusunun dogrudan cevabi
      sessionBytes: hb?.sessionBytes ?? null,
      pendingLogs: hb?.pendingLogs ?? null,
      model: hb?.model ?? null,
      lastError: hb?.lastError ?? null,
      /*
       * BU UCU PANEL OKUYOR - state'e konmadigi icin HEP BOS goruniyordu.
       *
       * clockNote        : saatin NEDEN supheli oldugu. "saat supheli" tek basina
       *                    hicbir mudahaleye yol gostermiyor; sebep (yeniden baslatma /
       *                    imzasiz kaynak / capa 40 gunluk / geriye giden zaman
       *                    reddedildi) dogrudan ne yapilacagini soyluyor.
       * lastInstallError : "guncelleme neden gelmedi?" sorusunun tek cevabi. Kurulum
       *                    sonucu asenkron geldigi icin lastError'dan AYRI tutuluyor.
       * timezone         : daypart'in hangi dilimde uygulandigi. Cihaz ile sunucu
       *                    ayrisirsa sabah kusagi yanlis saatte doner ve bu baska
       *                    hicbir yerde gorunmez.
       */
      clockNote: hb?.clockNote ?? null,
      lastInstallError: hb?.lastInstallError ?? null,
      timezone: hb?.timezone ?? null,
      /*
       * CIHAZ SAHIBI (DEVICE OWNER) DURUMU - PANELDE GORUNMESI SART.
       *
       * Proje kendi dokumantasyonunda "device owner olmadan bu is yurumez" diyor:
       * sessiz kurulum, kiosk, WiFi profili, planli reboot hepsi ona bagli. Buna
       * ragmen bu bilgi HICBIR uzaktan kanalda yoktu - yalnizca cihazin yanina gidip
       * teshis ekranini acarak gorulebiliyordu. Bir ROM degisikligi DPM politikalarini
       * dusurdugunde 50 cihaz kiosk'suz calisiyor ve heartbeat YESIL gonderiyordu;
       * problem ancak bir yolcu kumandayla uygulamadan cikinca ortaya cikiyordu.
       */
      deviceOwner: hb?.deviceOwner ?? null,
      // Uygulanamayan DPM politikalarinin listesi (bos = hepsi uygulandi).
      policyErrors: hb?.policyErrors ?? null
    }
  })

  /*
   * EVERGREEN KAPSAMI - "ekran bos kalmaz" sozu grup bazinda da tutmali.
   *
   * Evergreen artik grup suzgecine tabi (bkz. manifest.js). Dogru davranis ama bir
   * riski var: bir grubun hic evergreen'i kalmazsa o hattaki otobuslerin ekrani,
   * kampanyalari bittigi anda BOSALIR. Bu gizlice telafi edilmemeli, GORUNMELI:
   * panelde uyari olarak cikiyor. Ayni sekilde suresi gecmis ama hala ACIK duran
   * kampanyalar da bildiriliyor - onlar artik manifeste girmiyor ama panelde
   * "neden yayinda degil?" sorusunu dogurur.
   */
  const gruplar = [...new Set(Object.values(s.devices).filter((d) => !d.revoked).map((d) => d.group || 'default'))]
  const aktifKampanyalar = Object.values(s.campaigns).filter((c) => c.enabled)
  const evergreensizGruplar = gruplar.filter((g) => !aktifKampanyalar.some((c) =>
    c.evergreen && (!Array.isArray(c.groups) || c.groups.length === 0 || c.groups.includes(g))
  ))
  const suresiGecmis = aktifKampanyalar
    .filter((c) => !c.evergreen && c.validUntil && Date.parse(c.validUntil) < now)
    .map((c) => c.id)

  res.json({
    playlistVersion: s.playlistVersion,
    app: s.app,
    uyarilar: {
      // Bu gruplardaki otobusler, kampanyalari bittigi anda EKRANI BOS kalir.
      evergreensizGruplar,
      // Artik manifeste girmiyorlar; panelde kapatilmalari veya tarih uzatilmali.
      suresiGecmisKampanyalar: suresiGecmis
    },
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
  /*
   * itemSha DA validId'den GECER - tek basina varlik kontrolu YETMIYOR.
   *
   * `s.items['__proto__']` kalitim yoluyla Object.prototype'a cozulur ve DOGRUDUR,
   * yani eski `if (!s.items[b.itemSha])` kontrolu geciyordu. Kampanya
   * itemSha:'__proto__' ile kaydediliyor, manifest.js ayni sebeple onu gecerli
   * sayiyor ve file/size/sha256/chunks alanlari UNDEFINED olan bir oge uretiyordu -
   * JSON.stringify o alanlari tamamen atiyor. Cihazdaki ManifestParser bu alanlari
   * ZORUNLU okudugu icin parse TAMAMEN basarisiz oluyor: TUM filo senkrondan
   * dusuyor, sunucu 200 donmeye devam ediyor ve sebep hicbir yerde gorunmuyor.
   */
  if (!validId(b.itemSha) || !Object.hasOwn(s.items, b.itemSha)) {
    return res.status(400).json({ error: 'icerik bulunamadi' })
  }

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

  /*
   * TARIHLER AYRISTIRILABILIR OLMAK ZORUNDA - VE NORMALIZE EDILIR.
   *
   * Eski hali yalnizca validUntil'in VAR OLDUGUNA bakiyordu, TARIH OLDUGUNA
   * bakmiyordu. Cihaz tarafi katı ISO-8601 instant bekler (`Instant.parse`);
   * ayristiramazsa validUntil null olur ve evergreen olmayan oge KALICI OLARAK
   * hicbir zaman uygun olmaz. Panel ise `new Date(...)` kullandigi icin ayni degeri
   * sorunsuz gosterir - yani "31.12.2026" yazan bir kampanya panelde dogru gorunur,
   * 50 otobus dosyayi indirir (bant genisligi harcanir) ve ucretli reklam HIC
   * yayinlanmaz. Ilk isaret ay sonunda raporun bos olmasidir.
   *
   * Cozum: burada Date.parse ile dogrula ve toISOString() ile normalize et. Boylece
   * cihaza giden deger her zaman Instant.parse'in kabul ettigi bicimde olur.
   */
  const tarih = (ad, v) => {
    if (v === null || v === undefined || String(v).trim() === '') return null
    const t = Date.parse(v)
    if (!Number.isFinite(t)) {
      const e = new Error(`${ad} bir tarih olarak okunamadi: "${v}". ISO-8601 kullanin, orn. 2026-12-31T23:59:00Z`)
      e.alan = ad
      throw e
    }
    return new Date(t).toISOString()
  }
  let validFrom, validUntil
  try {
    validFrom = evergreen ? null : tarih('validFrom', b.validFrom)
    validUntil = evergreen ? null : tarih('validUntil', b.validUntil)
  } catch (e) {
    return res.status(400).json({ error: 'gecersiz tarih', detail: e.message })
  }
  // Ters aralik: hicbir zaman yayinlanamayacak bir kampanya. Kabul etmek, operatore
  // "kaydedildi" deyip reklami hic oynatmamak olurdu.
  if (validFrom && validUntil && Date.parse(validFrom) >= Date.parse(validUntil)) {
    return res.status(400).json({
      error: 'ters tarih araligi',
      detail: `bitis (${validUntil}) baslangictan (${validFrom}) sonra olmali - bu kampanya hic yayinlanamazdi`
    })
  }

  s.campaigns[id] = {
    id,
    itemSha: b.itemSha,
    title: b.title || id,
    advertiser: b.advertiser || '',
    validFrom,
    validUntil,
    dayparts,
    /*
     * AGIRLIK UST SINIRI - CIHAZLA AYNI SINIR.
     *
     * Sunucu sinirsiz kabul ediyordu, cihaz ise Weighting icinde 20'de KIRPIYOR.
     * Operator "bu reklam 100 kat donsun" diye 100 yazdiginda panel 100 gosteriyor,
     * cihaz 20 uyguluyordu: reklamverene satilan sey ile yayinlanan sey ayrisiyor ve
     * fark hicbir yerde gorunmuyordu. Sinir tek yerde olmali ve operatorun GORDUGU
     * deger uygulanan deger olmali.
     */
    weight: Math.min(AGIRLIK_UST_SINIR, Math.max(1, Math.trunc(Number(b.weight)) || 1)),
    groups: Array.isArray(b.groups) ? b.groups : [],
    evergreen,
    enabled: b.enabled !== false,
    updatedAt: new Date().toISOString()
  }
  save()
  const v = bumpPlaylistVersion()
  res.json({ ok: true, campaign: s.campaigns[id], playlistVersion: v })
})

/*
 * DELETE UCLARI: KALITILAN ANAHTARLAR "VAR" SAYILMAZ.
 *
 * `s.campaigns['__proto__']` kalitim yoluyla dogru bir deger doner, yani eski
 * `if (!s.campaigns[id])` kontrolu geciyordu; `delete` ise kendi ozelligi olmadigi
 * icin HICBIR SEY silmeyip true donuyordu. Sonuc: operator `{ok:true}` goruyor,
 * kampanyanin silindigine inaniyor - kampanya bir sonraki manifestte oynamaya devam
 * ediyor. Ustelik playlistVersion bosa artiyor ve 50 otobusun tamami panelde
 * "playlistVersion geride" olarak isaretleniyor.
 */
adminRouter.delete('/campaign/:id', (req, res) => {
  const s = db()
  if (!validId(req.params.id)) return res.status(400).json({ error: 'gecersiz id' })
  if (!Object.hasOwn(s.campaigns, req.params.id)) return res.status(404).json({ error: 'yok' })
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
    /*
     * VAR OLAN ALANLARI EZMEYIN.
     *
     * Bu uc alan onceden govdede yoksa VARSAYILANA SIFIRLANIYORDU. Somut zarar:
     * tokeni yenilemek ya da kanarya atamak icin ayni cihazi yeniden POST etmek
     *   - grubunu 'default' yapiyor (o otobus artik hattinin kampanyalarini almaz)
     *   - IPTAL EDILMIS bir cihazi sessizce YENIDEN AKTIF ediyor
     * Ikincisi bir guvenlik gerilemesi: calinan/sokulen bir stick'i iptal ettikten
     * sonra dikkatsiz bir yeniden kayit onu tekrar yayina aliyordu.
     */
    label: b.label ?? existing?.label ?? b.id,
    group: b.group ?? existing?.group ?? 'default',
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
    note: b.note ?? existing?.note ?? '',
    revoked: b.revoked === undefined ? !!existing?.revoked : !!b.revoked,
    createdAt: existing?.createdAt || new Date().toISOString()
  }
  save()
  res.json({ ok: true, device: s.devices[b.id] })
})

adminRouter.delete('/device/:id', (req, res) => {
  const s = db()
  if (!validId(req.params.id)) return res.status(400).json({ error: 'gecersiz id' })
  if (!Object.hasOwn(s.devices, req.params.id)) return res.status(404).json({ error: 'yok' })
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

    /*
     * rolloutGroup DOGRULANIR - dogrulanmadiginda kademeli yayimi SESSIZCE KAPATIR.
     *
     * `Number('hepsi')` = NaN; NaN db.json'a `null` olarak yazilir, manifest.js'teki
     * `?? 0` NaN'i yakalamaz, cihazda `optInt(...,0)` 0 olur ve kural `grup <=
     * rolloutGroup` oldugu icin 0 HICBIR cihazi kapsamaz. Yani kritik bir duzeltme
     * "yayimlandi" gorunur, playlistVersion artar ve 50 otobusun HICBIRI guncellemeyi
     * indirmez. 4G ve uzaktan komut kanali olmadigi icin tek geri bildirim, gunler
     * sonra panelde appVersion sutununun hala eski surumu gostermesidir.
     */
    const rg = grupSayisiDogrula(req.query.rolloutGroup, 1)
    if (rg === null) {
      return res.status(400).json({ error: `rolloutGroup 1..${config.rolloutGroups} arasi tam sayi olmali` })
    }

    const name = `reklam-${versionCode}.apk`
    const file = path.join(paths.app, name)

    /*
     * AYNI versionCode'u SESSIZCE EZME.
     *
     * Yeniden yukleme sirasinda dosya yerinde degistiriliyorsa, o pencerede gelen
     * otobus eski manifestteki chunk hash'leriyle dogrulamayi surekli basarisiz kilar
     * ve 3 dakikasini bosa harcar. Bilincli bir ?force=1 istiyoruz.
     */
    if (fs.existsSync(file) && req.query.force !== '1') {
      return res.status(409).json({
        error: `versionCode ${versionCode} zaten yayimli`,
        detail: 'Yeni bir versionCode kullanin ya da bilincli olarak ?force=1 ekleyin.'
      })
    }

    /*
     * APK ATOMIK YAYIMLANIR - DOGRUDAN YAYINDAKI DOSYANIN USTUNE AKITILMAZ.
     *
     * Eski hali govdeyi dogrudan `app/reklam-<v>.apk` uzerine akitiyordu ve
     * streamToFile hata yolunda HEDEFI SILIYORDU. Ama `s.app` yalnizca basarida
     * guncellendiginden, basarisiz bir yuklemeden sonra manifest halen o dosyayi
     * (eski sha256 + eski chunk listesiyle) reklam ediyordu - dosya ise diskte YOKTU.
     * Somut sonuc: govdesiz/kesik tek bir istek, tum filonun APK'sini 404'e cevirip
     * uygulama guncellemesini (critical bayragi dahil) KALICI olarak imkansiz
     * kiliyordu; panel "surum yayimlandi" gostermeye devam ediyordu.
     *
     * Video tarafi (ingest) zaten gecici dosya + rename kullaniyor; ayni deseni
     * buraya da getiriyoruz.
     */
    const gecici = path.join(paths.incoming, `apk-${Date.now()}-${randomToken(6)}.apk`)
    let sha, size, chunks
    try {
      await streamToFile(req, gecici)
      sha = await sha256File(gecici)
      ;({ size, chunks } = await buildChunks(gecici))
    } catch (e) {
      fs.rmSync(gecici, { force: true })
      return res.status(400).json({ error: String(e.message || e) })
    }

    // Ancak her sey hesaplandiktan SONRA yayina al.
    fs.renameSync(gecici, file)

    const s = db()
    s.app = {
      versionCode,
      versionName: String(req.query.versionName || versionCode),
      file: `app/${name}`,
      size,
      sha256: sha,
      chunks,
      rolloutGroup: rg,
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
  // Dogrulanmamis bir deger (NaN / 0 / -1) guncellemeyi tum filoya sessizce kapatirdi.
  const rg = grupSayisiDogrula(req.body?.rolloutGroup, config.rolloutGroups)
  if (rg === null) {
    return res.status(400).json({ error: `rolloutGroup 1..${config.rolloutGroups} arasi tam sayi olmali` })
  }
  s.app.rolloutGroup = rg
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

  /*
   * SAKLAMA SURELERI DOGRULANIR - DOGRULANMADIGINDA HER SEYI SILER.
   *
   * Eski hali `Number(req.body?.kanitGun ?? 90)` idi. `"90 gun"` -> NaN ve filtre
   * `if (st.mtimeMs >= kanitSiniri) continue` NaN karsilastirmasinda HER ZAMAN false
   * verir: hicbir dosya atlanmaz, dizinin TAMAMI silinir. `0` ise ayni sonucu
   * deterministik olarak uretir. Kaybedilenler: reklamverene karsi tek gorsel dayanak
   * olan kanit kareleri ve projenin temel sorusunun ("3 dakika yetiyor mu?") tek veri
   * kaynagi olan heartbeat gecmisi. Yanit 200 doner, panel "N oge silindi" yazar.
   */
  const saklama = (ad, ham, varsayilan) => {
    if (ham === undefined || ham === null || String(ham).trim() === '') return varsayilan
    const n = Number(ham)
    if (!Number.isInteger(n) || n < 7) return null
    return n
  }
  const kanitGun = saklama('kanitGun', req.body?.kanitGun, 90)
  const gecmisGun = saklama('gecmisGun', req.body?.gecmisGun, 180)
  if (kanitGun === null || gecmisGun === null) {
    return res.status(400).json({
      error: 'gecersiz saklama suresi',
      detail: 'kanitGun ve gecmisGun en az 7 olan tam sayi olmali (gun cinsinden). ' +
        'Dogrulanmamis bir deger TUM kanit karelerini ve TUM heartbeat gecmisini silerdi.'
    })
  }

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

  /*
   * YETIM DOSYALAR - DISKI GERCEKTEN DOLDURAN SEY.
   *
   * Uc, kayitli ogelerden yola cikiyordu; oysa en buyuk dosyalar tam olarak KAYIT
   * DISI kalanlar:
   *  - incoming/ : /upload ham govdeyi 2 GB'a kadar buraya akitiyor. Yukleme
   *    sirasinda sunucu yeniden baslarsa (elektrik, OOM, deploy) catch blogu HIC
   *    calismaz ve 2 GB'lik ham dosya kalici olarak diskte kalir.
   *  - content/  : ingest'in rename'i ile save() arasindaki bir kesinti
   *    `content/<sha>.mp4` dosyasini kaydi olmadan birakir.
   * Ikisini de ne bu uc ne baska bir sey siliyordu - oysa ucun kendi aciklamasi
   * "bir yil sonra disk doldu diye aranmamak icin" diyor. Dolu diskte save() ve
   * appendPlayLogs yazamaz, yani FATURA loglari da alinamaz.
   *
   * incoming/ icin 24 saat esigi: devam eden bir yuklemeyi kazara silmemek icin.
   */
  const YETIM_ESIK_MS = 24 * 3600 * 1000
  const yetimIncoming = []
  for (const f of fs.readdirSync(paths.incoming)) {
    const tam = path.join(paths.incoming, f)
    let st
    try { st = fs.statSync(tam) } catch { continue }
    if (!st.isFile()) continue
    if (Date.now() - st.mtimeMs < YETIM_ESIK_MS) continue
    yetimIncoming.push({ dosya: f, size: st.size })
    bayt += st.size
    if (!kuruProva) fs.rmSync(tam, { force: true })
  }

  // content/ altinda s.items'ta karsiligi olmayan dosyalar. DIKKAT: silinen ogeler
  // yukarida s.items'tan cikarildigi icin bu tarama onlari da yakalar - zararsiz,
  // dosyalari da orada silindi.
  const kayitli = new Set(Object.values(s.items).map((i) => `${i.sha256}.mp4`))
  const yetimIcerik = []
  for (const f of fs.readdirSync(paths.content)) {
    if (kayitli.has(f)) continue
    const tam = path.join(paths.content, f)
    let st
    try { st = fs.statSync(tam) } catch { continue }
    if (!st.isFile()) continue
    yetimIcerik.push({ dosya: f, size: st.size })
    bayt += st.size
    if (!kuruProva) fs.rmSync(tam, { force: true })
  }

  if (!kuruProva) save()

  res.json({
    kuruProva,
    eskiHeartbeatGecmisi: eskiGecmis,
    silinen: adlar.length + eskiApk.length + eskiKanit + eskiGecmis +
      yetimIncoming.length + yetimIcerik.length,
    kazanilanBayt: bayt,
    icerikler: adlar,
    apkler: eskiApk,
    eskiKanitKaresi: eskiKanit,
    kanitSaklamaGun: kanitGun,
    gecmisSaklamaGun: gecmisGun,
    // Kayit disi kalmis dosyalar: kuru provada da gorunur olmalari onemli.
    yetimYuklemeler: yetimIncoming,
    yetimIcerikler: yetimIcerik,
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

  const aralik = aralikCoz(from, to)
  if (aralik.hata) return res.status(400).json(aralik.hata)

  const agg = new Map()
  for (const h of readHeartbeatHistory(aralik.from, aralik.to)) {
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
  res.set('Content-Disposition', `attachment; filename="pencere-raporu-${aralik.from}_${aralik.to}.csv"`)
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

  // Aralik SINIRLI: verilmezse son 90 gun (bkz. aralikCoz). Sinirsiz okuma, sunucuyu
  // o sure boyunca tum otobuslere kapatirdi.
  const aralik = aralikCoz(from, to)
  if (aralik.hata) return res.status(400).json(aralik.hata)

  /*
   * GUN SECIMI - FATURANIN EN HASSAS NOKTASI.
   *
   * Fatura gunu artik YAZMA ANINDA, sunucuda kararlastiriliyor ve satirda
   * `faturaGunu` olarak duruyor (bkz. store.faturaGunuHesapla). Dosya adi da o
   * gunden geldigi icin dosya suzgeci ile satirin gunu ASLA ayrisamaz - eski
   * halde saati 1970'te kalmis bir cihazin kayitlari logs/1970-01-01.ndjson'a
   * gidiyor ve hicbir fatura raporunda GORUNMUYORDU.
   *
   * Eski satirlarda (bu degisiklikten once yazilmis) alan yok: orada da dosya adi
   * cihazin gunuydu, yani startedAt ile tutarli - geriye donuk olarak ondan
   * turetiyoruz.
   */
  const gunu = (r) => {
    if (typeof r.faturaGunu === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(r.faturaGunu)) return r.faturaGunu
    const cihaz = String(r.startedAt || '').slice(0, 10)
    if (/^\d{4}-\d{2}-\d{2}$/.test(cihaz)) return cihaz
    return String(r.receivedAt || '').slice(0, 10)
  }

  const rows = readPlayLogs(aralik.from, aralik.to).filter((r) => {
    if (!r.completed) return false
    if (campaign && r.itemId !== campaign) return false
    const g = gunu(r)
    if (g < aralik.from || g > aralik.to) return false
    return true
  })

  const agg = new Map()
  for (const r of rows) {
    const gun = gunu(r)
    const key = `${r.itemId}\u0000${r.deviceId}\u0000${gun}`
    const cur = agg.get(key) || { itemId: r.itemId, deviceId: r.deviceId, day: gun, plays: 0, totalMs: 0, untrustedClock: 0 }
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
  // Dosya adi ETKIN araligi tasir: operator hangi donemi indirdigini sonradan da bilsin
  // (tarih vermediginde varsayilan pencere uygulandigi icin bu onemli).
  res.set('Content-Disposition', `attachment; filename="oynatma-raporu-${aralik.from}_${aralik.to}.csv"`)
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
/**
 * rolloutGroup dogrulama: 1..config.rolloutGroups arasi tam sayi, degilse null.
 * Bos/eksik deger `varsayilan`a duser.
 */
function grupSayisiDogrula (ham, varsayilan) {
  if (ham === undefined || ham === null || String(ham).trim() === '') return varsayilan
  const n = Number(ham)
  if (!Number.isInteger(n) || n < 1 || n > config.rolloutGroups) return null
  return n
}

/**
 * RAPOR ARALIGI SINIRLI - HEM BELLEK HEM OLAY DONGUSU ICIN.
 *
 * Rapor uclari ilgili TUM gunlerin NDJSON/heartbeat dosyalarini SENKRON okuyup
 * RAM'e aliyor. Aralik verilmezse bu "tum gecmis" demektir: bir yil sonra 50 cihaz
 * x 365 gun x onlarca satir, hem yuz MB'lari bellege alir hem de okuma/ayristirma
 * suresince Node'un tek is parcacigini kilitler - tam o anda noktaya giren otobus
 * manifest istegi icin zaman asimina ugrar ve 3 dakikalik penceresini kaybeder.
 *
 * Operator raporu her zaman bir fatura donemi icin ceker, yani aralik zaten var.
 * Ust sinir 400 gun: yillik rapor + pay.
 */
const RAPOR_MAX_GUN = Number(process.env.RAPOR_MAX_GUN || 400)

/** Varsayilan pencere: operator tarih vermezse son N gun. */
const RAPOR_VARSAYILAN_GUN = Number(process.env.RAPOR_VARSAYILAN_GUN || 90)

const GUN_BICIMI = /^\d{4}-\d{2}-\d{2}$/
const gunEkle = (gunStr, delta) =>
  new Date(Date.parse(`${gunStr}T00:00:00Z`) + delta * 86_400_000).toISOString().slice(0, 10)

/**
 * Istenen araligi coz ve SINIRLA.
 * @returns {{from: string, to: string}} veya {{hata: object}}
 */
function aralikCoz (from, to) {
  for (const [ad, v] of [['from', from], ['to', to]]) {
    if (v && !GUN_BICIMI.test(v)) return { hata: { error: `${ad} YYYY-AA-GG biciminde olmali`, gelen: v } }
  }
  const bugun = new Date().toISOString().slice(0, 10)
  // Tarih verilmezse "tum gecmis" DEGIL, son RAPOR_VARSAYILAN_GUN gun.
  const bit = to || bugun
  const bas = from || gunEkle(bit, -(RAPOR_VARSAYILAN_GUN - 1))
  if (bas > bit) return { hata: { error: 'from, to`dan sonra olamaz', from: bas, to: bit } }
  const gun = Math.round((Date.parse(`${bit}T00:00:00Z`) - Date.parse(`${bas}T00:00:00Z`)) / 86_400_000) + 1
  if (!Number.isFinite(gun)) return { hata: { error: 'tarihler okunamadi', from: bas, to: bit } }
  if (gun > RAPOR_MAX_GUN) {
    return {
      hata: {
        error: `aralik en fazla ${RAPOR_MAX_GUN} gun olabilir`,
        istenen: gun,
        detail: 'Daha genis aralik tum gunleri bellege okur ve sunucu o sure boyunca ' +
          'hicbir otobuse cevap veremez. Raporu donemlere bolun.'
      }
    }
  }
  return { from: bas, to: bit }
}

/**
 * Kampanya agirligi ust siniri.
 * Cihaz tarafindaki Weighting.MAX_AGIRLIK ile AYNI olmak zorunda: aksi halde panelde
 * gorunen agirlik ile yayinda uygulanan agirlik ayrisir ve fark hicbir yerde gorunmez.
 */
const AGIRLIK_UST_SINIR = 20

/** Pozitif tam sayi mi? Degilse null. (Number(null)===0 tuzagi icin - bkz. /device) */
function pozitifTam (v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isInteger(n) && n > 0 ? n : null
}

/*
 * DISA ACIK: sozlesmenin SUNUCU tarafi da test edilebilir olmali.
 * Cihaz tarafi (RolloutGroup.kt) kendi testine sahipti; bu fonksiyon degilse
 * "iki taraf ayni sonucu uretiyor" iddiasi yalnizca YARISI kilitlenmis olurdu.
 */
export function rolloutGroupOf (deviceId, groups) {
  if (groups <= 1) return 1
  let h = 0
  for (let i = 0; i < deviceId.length; i++) h = (Math.imul(31, h) + deviceId.charCodeAt(i)) | 0
  // Math.abs(-2147483648) === 2147483648 (32-bit tasma); Kotlin'de abs ayni degeri
  // negatif dondurur. Iki tarafin ayni davranmasi icin bu durum ozel ele aliniyor.
  const positive = h === -2147483648 ? 0 : Math.abs(h)
  return 1 + (positive % groups)
}
