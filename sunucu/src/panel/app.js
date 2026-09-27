/* eslint-env browser */
const $ = (id) => document.getElementById(id)

/**
 * HTML kacisi.
 *
 * NEDEN ZORUNLU: bu paneldeki verilerin bir kismi CIHAZDAN geliyor (heartbeat'teki
 * lastError gibi), bir kismi serbest metin (kampanya basligi, reklamveren adi,
 * yuklenen dosya adi). Kacis yapilmadan innerHTML'e basilirsa, ele gecirilmis tek
 * bir otobus cihazi heartbeat'e <img src=x onerror=...> yazarak panelde kod
 * calistirabilir ve localStorage'daki ADMIN TOKENINI calabilir.
 *
 * Cihaz id / grup gibi alanlar sunucuda zaten dogrulaniyor ama burada ayrim
 * yapmiyoruz: DISARIDAN gelen her sey kacisa girer.
 */
function esc (value) {
  if (value === null || value === undefined) return ''
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
let TOKEN = localStorage.getItem('adminToken') || ''
$('token').value = TOKEN

function log (msg) {
  $('log').textContent = `${new Date().toLocaleTimeString('tr-TR')}  ${msg}\n` + $('log').textContent
}

/** Kampanya bolumunun KENDI durum satiri - hata baska bolumun log kutusuna dusmesin. */
function kampanyaMesaji (msg, hata = false) {
  const el = $('campaignMsg')
  if (!el) return log(msg)
  el.textContent = msg
  el.style.color = hata ? 'var(--bad)' : 'var(--dim)'
  el.style.display = msg ? 'block' : 'none'
}

async function api (path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    headers: { ...(opts.headers || {}), authorization: `Bearer ${TOKEN}` }
  })
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`)
  return res.headers.get('content-type')?.includes('json') ? res.json() : res.text()
}

async function init () {
  TOKEN = $('token').value.trim()
  localStorage.setItem('adminToken', TOKEN)
  await refresh()
}

function fmtAgo (iso) {
  if (!iso) return '<span class="dim">hiç</span>'
  const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (mins < 60) return `${mins} dk önce`
  if (mins < 1440) return `${Math.round(mins / 60)} sa önce`
  return `${Math.round(mins / 1440)} gün önce`
}

/*
 * BAGLANTI DURUMU - DONMUS PANEL ILE CALISAN PANEL AYIRT EDILEBILMELI.
 *
 * Eski hali `setInterval(... refresh().catch(() => {}))` idi: bos catch. refresh
 * basarisiz olunca hicbir DOM yazimi yapilmiyor, tablo son BASARILI render'da
 * kaliyordu. Panelde "son guncelleme" damgasi da olmadigi icin operator 6 saat
 * once donmus bir ekrana bakip "tum otobusler iyi, son senkron 4 dk once" goruyordu -
 * oysa o arada 12 otobus EKRAN BOS'a dusmus olabilir. Bu panel, sistemin en yuksek
 * oncelikli alarminin TEK izleme kanali; sessiz donma kabul edilemez.
 */
let sonBasarili = null
let ardilHata = 0

function baglantiDurumu () {
  const el = $('baglanti')
  if (!el) return
  if (ardilHata === 0) {
    el.style.display = 'none'
    el.textContent = ''
  } else {
    el.style.display = 'block'
    const ne = sonBasarili ? `son güncelleme ${sonBasarili.toLocaleTimeString('tr-TR')}` : 'hiç veri alınamadı'
    el.textContent = `BAĞLANTI KOPTU (${ardilHata} deneme başarısız) — ${ne}. Ekrandaki veriler BAYAT.`
  }
  // Iki ardil hatadan sonra tabloyu da soluklastir: bakan kisi bir sey yapmasa bile
  // gormezden gelemesin.
  const ana = $('tablolar')
  if (ana) ana.style.opacity = ardilHata >= 2 ? '0.45' : '1'
}

function refreshBasarili () {
  sonBasarili = new Date()
  ardilHata = 0
  const el = $('sonGuncelleme')
  if (el) el.textContent = `· son güncelleme ${sonBasarili.toLocaleTimeString('tr-TR')}`
  baglantiDurumu()
}

function refreshBasarisiz (e) {
  ardilHata += 1
  baglantiDurumu()
  // Ilk hatayi ve her 10. hatayi logla: log kutusu dolup okunmaz hale gelmesin.
  if (ardilHata === 1 || ardilHata % 10 === 0) log(`YENİLEME HATASI (${ardilHata}): ${e.message}`)
}

/*
 * FORMA DOKUNULDUYSA OTOMATIK YENILEME FORMU BOZMAZ.
 *
 * `$('cItem').innerHTML = ...` her yenilemede option dugumlerini yok edip yeniden
 * kuruyordu; hicbirine `selected` verilmediginden tarayici selectedIndex'i 0'a
 * dusuruyordu. Yenileme ise operatorun ne yaptigina BAKILMAKSIZIN 30 saniyede bir
 * calisiyordu - form doldurmak (baslik, reklamveren, iki tarih secici, agirlik,
 * gruplar) 30 saniyeyi gecer. Sonuc: operator 3. videoyu secip kaydediyor, manifest
 * BASKA reklamverenin videosunu "A Markasi" basligiyla tasiyor, 50 otobuste yanlis
 * reklam yayinlaniyor ve sunucu tarafinda hicbir tutarsizlik gorunmuyor (ilk oge de
 * gecerli bir icerik).
 */
let formKirli = false
const KAMPANYA_ALANLARI = ['cItem', 'cTitle', 'cAdv', 'cFrom', 'cUntil', 'cWeight', 'cGroups', 'cDayparts', 'cEver']

function formIzle () {
  for (const id of KAMPANYA_ALANLARI) {
    const el = $(id)
    if (!el) continue
    el.addEventListener('input', () => { formKirli = true; kampanyaMesaji('') })
    el.addEventListener('change', () => { formKirli = true })
  }
  const ever = $('cEver')
  if (ever) {
    // Evergreen'in suresi yoktur: alanlari kapatmak "neden tarih girdim de
    // kaydedilmedi?" sorusunu bastan onler.
    const uygula = () => {
      for (const id of ['cFrom', 'cUntil']) {
        const el = $(id)
        if (el) { el.disabled = ever.checked; el.style.opacity = ever.checked ? '0.4' : '1' }
      }
    }
    ever.addEventListener('change', uygula)
    uygula()
  }
}

/** Formdaki bir alan odakta mi? (aktif yazim sirasinda yenileme yapilmaz) */
function formOdakta () {
  const a = document.activeElement
  return !!a && KAMPANYA_ALANLARI.includes(a.id)
}

/** Icerik listesini yalnizca DEGISTIYSE yeniden kur ve secimi KORU. */
let itemImzasi = ''
function icerikSecimiGuncelle (items) {
  const imza = items.map((i) => i.sha256).join(',')
  const sec = $('cItem')
  if (!sec) return
  if (imza === itemImzasi) return
  const onceki = sec.value
  sec.innerHTML = items.map((i) => {
    const sure = i.durationBilinmiyor
      ? 'süre BİLİNMİYOR'
      : `${Math.round(i.durationMs / 1000)} sn`
    return `<option value="${esc(i.sha256)}">${esc(i.originalName)} — ${(i.size / 1e6).toFixed(1)} MB / ${esc(sure)}</option>`
  }).join('')
  itemImzasi = imza
  if (onceki && items.some((i) => i.sha256 === onceki)) sec.value = onceki
}

/** Kampanyanin hesaplanmis durumu: operator "neden yayinda degil?" diye sormasin. */
function kampanyaDurumu (c) {
  const now = Date.now()
  if (!c.enabled) return { metin: 'kapalı', renk: 'var(--dim)' }
  if (c.evergreen) return { metin: 'evergreen', renk: 'var(--ok)' }
  const f = c.validFrom ? Date.parse(c.validFrom) : null
  const u = c.validUntil ? Date.parse(c.validUntil) : null
  if (f && u && f >= u) return { metin: 'TERS ARALIK', renk: 'var(--bad)' }
  if (u && u < now) return { metin: 'süresi geçmiş', renk: 'var(--bad)' }
  if (f && f > now) return { metin: 'bekliyor', renk: 'var(--warn)' }
  return { metin: 'aktif', renk: 'var(--ok)' }
}

async function refresh () {
  const s = await api('/api/admin/state')
  $('ver').textContent = `liste sürümü: ${s.playlistVersion}`

  /*
   * UYARILAR - sessizce dogru olmak yetmez, GORUNMESI gerekir.
   *
   * evergreensizGruplar: evergreen artik grup suzgecine tabi (operatorun secimi
   *   uygulaniyor). Dogru davranis ama bir grubun hic evergreen'i kalmazsa o hattaki
   *   otobusler kampanyalari bittigi anda EKRANI BOS kalir - bu sistemin en kotu
   *   sonucu. Gizlice telafi etmek yerine soyluyoruz.
   * suresiGecmisKampanyalar: artik manifeste girmiyorlar (pencereyi asla
   *   oynatilamayacak dosyaya harcamamak icin) ama "acik" gorundukleri surece
   *   "neden yayinda degil?" sorusunu dogururlar.
   */
  const u = s.uyarilar || {}
  const satirlar = []
  if (u.evergreensizGruplar?.length) {
    satirlar.push(`<strong>EVERGREEN YOK:</strong> ${esc(u.evergreensizGruplar.join(', '))} ` +
      'grubundaki otobüsler, kampanyaları bittiği anda ekranı boş kalır.')
  }
  if (u.suresiGecmisKampanyalar?.length) {
    satirlar.push(`<strong>SÜRESİ GEÇMİŞ ama açık:</strong> ${esc(u.suresiGecmisKampanyalar.join(', '))} ` +
      '— yayına girmiyorlar; kapatın veya bitiş tarihini uzatın.')
  }
  // Cihaz sahibi olmayan cihaz: sessiz kurulum, kiosk, WiFi ve planli reboot
  // CALISMIYOR demektir. Projenin temel varsayimi dustugu icin en ust siraya.
  const sahipsiz = s.devices.filter((d) => d.deviceOwner === false).map((d) => d.id)
  if (sahipsiz.length) {
    satirlar.unshift(`<strong>CİHAZ SAHİBİ DEĞİL:</strong> ${esc(sahipsiz.join(', ')) } ` +
      '— sessiz güncelleme, kiosk ve WiFi profili çalışmaz. Fabrika ayarlarına dönüp yeniden provizyon gerekir.')
  }
  const sureSiz = (s.items || []).filter((i) => i.durationBilinmiyor).map((i) => i.originalName)
  if (sureSiz.length) {
    satirlar.push(`<strong>SÜRE ÖLÇÜLEMEDİ:</strong> ${esc(sureSiz.join(', '))} ` +
      '— fatura süresi ölçülen zamana düşer. ffprobe kurulu mu? Dosyayı yeniden yükleyin.')
  }
  const uy = $('uyarilar')
  if (uy) {
    uy.innerHTML = satirlar.map((x) => `<div>${x}</div>`).join('')
    uy.style.display = satirlar.length ? 'block' : 'none'
  }

  $('devices').innerHTML = s.devices.map((d) => {
    // EKRAN BOS her seyin onunde gelir: reklamveren para odedi, ekran siyah.
    const ekranBos = d.playableItems === 0
    const sahipsizMi = d.deviceOwner === false
    const cls = (d.revoked || ekranBos || sahipsizMi) ? 'bad' : (d.stale || d.safeMode) ? 'warn' : d.lastSeenAt ? 'ok' : 'warn'
    const durum = d.revoked ? 'iptal'
      : ekranBos ? 'EKRAN BOŞ'
        : sahipsizMi ? 'SAHİP DEĞİL'
          : d.safeMode ? 'güvenli mod'
            : d.stale ? 'bayat' : 'iyi'
    const ready = d.readyItems != null
      ? `${d.readyItems}/${d.totalItems}` + (d.badItems ? ` (${d.badItems} bozuk)` : '')
      : '–'
    // Son pencerede inen bayt: 3 dakikanin yetip yetmedigini dogrudan gosterir
    const pencere = d.sessionBytes != null
      ? (d.sessionBytes >= 1e6 ? (d.sessionBytes / 1e6).toFixed(1) + ' MB' : Math.round(d.sessionBytes / 1e3) + ' KB')
      : '–'
    return `<tr>
      <td><span class="dot ${cls}"></span>${durum}</td>
      <td>${esc(d.label || d.id)}<div class="dim">${esc(d.id)}</div></td>
      <td>${esc(d.group)}</td>
      <td title="${d.rolloutPinned ? 'elle atandi (kanarya)' : 'cihaz id hash inden otomatik'}">${
        d.rolloutPinned ? `<strong>${esc(d.rolloutGroup)}</strong>` : `<span class="dim">${esc(d.rolloutGroup ?? '–')}</span>`
      }</td>
      <td>${fmtAgo(d.lastSeenAt)}</td>
      <td>${esc(d.playlistVersion ?? '–')}</td>
      <td>${esc(ready)}</td>
      <td>${esc(d.appVersion ?? '–')}</td>
      <td title="son senkron penceresinde inen bayt">${pencere}</td>
      <td>${d.freeBytes != null ? (d.freeBytes / 1e9).toFixed(1) + ' GB' : '–'}</td>
      <td>${d.rssi != null ? Number(d.rssi) + ' dBm' : '–'}</td>
      <td title="${esc(d.clockNote || '')}">${d.clockTrusted === false ? `<span style="color:var(--bad)">şüpheli</span><div class="dim">${esc(String(d.clockNote || '').slice(0, 28))}</div>` : d.clockTrusted === true ? 'iyi' : '–'}</td>
      <td class="dim">${esc(String(d.lastError || '').slice(0, 60))}${
        d.lastInstallError ? `<div style="color:var(--warn)">${esc(String(d.lastInstallError).slice(0, 60))}</div>` : ''
      }${
        d.policyErrors ? `<div style="color:var(--bad)">politika: ${esc(String(d.policyErrors).slice(0, 60))}</div>` : ''
      }</td>
    </tr>`
  }).join('')

  // Form doldurulurken video secimini SIFIRLAMA (bkz. formKirli yorumu).
  icerikSecimiGuncelle(s.items || [])

  $('campaigns').innerHTML = s.campaigns.map((c) => {
    const st = kampanyaDurumu(c)
    return `<tr>
    <td>${esc(c.title)}<div class="dim">${esc(c.id)}</div></td>
    <td>${esc(c.advertiser || '–')}</td>
    <td style="color:${st.renk}">${esc(st.metin)}</td>
    <td>${c.validFrom ? esc(new Date(c.validFrom).toLocaleString('tr-TR')) : '–'}</td>
    <td>${c.validUntil ? esc(new Date(c.validUntil).toLocaleString('tr-TR')) : '–'}</td>
    <td>${esc((c.dayparts || []).join(', ')) || '<span class="dim">gün boyu</span>'}</td>
    <td>${Number(c.weight) || 1}</td>
    <td>${esc((c.groups || []).join(', ') || 'hepsi')}</td>
    <td><button class="sec" data-sil="${esc(c.id)}">sil</button></td>
  </tr>`
  }).join('')

  // onclick="delCampaign('...')" yerine olay delegasyonu:
  // kimlik HTML metnine gomulmedigi icin tirnak kacirma ihtimali tamamen ortadan kalkar.
  $('campaigns').querySelectorAll('button[data-sil]').forEach((b) => {
    b.addEventListener('click', () => delCampaign(b.dataset.sil))
  })

  $('appInfo').textContent = s.app
    ? `sürüm ${s.app.versionName} (${s.app.versionCode}) · rollout grubu ≤ ${s.app.rolloutGroup} · ${(s.app.size / 1e6).toFixed(1)} MB`
    : 'henüz APK yayımlanmadı'

  refreshBasarili()
}

async function addDevice () {
  try {
    const r = await api('/api/admin/device', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        id: $('dId').value.trim(),
        label: $('dLabel').value.trim(),
        group: $('dGroup').value.trim() || 'default',
        // Bos ise ALAN HIC GONDERILMIYOR: sunucu o zaman cihaz id hash'inden
        // dagitir. 0 gondermek "grup 0" demek olurdu ve her guncellemeyi bu
        // otobuse ilk gonderirdi - istenmeyen bir kanarya.
        ...($('dRollout').value.trim() ? { rolloutGroup: Number($('dRollout').value) } : {})
      })
    })
    log(`Cihaz eklendi. TOKEN (bir daha gösterilmez): ${r.device.token}`)
    await refresh()
  } catch (e) { log('HATA: ' + e.message) }
}

/*
 * YUKLEME: ILERLEME, BUTON KILIDI VE ASAMA BILGISI.
 *
 * fetch ile yukleme ilerlemesi okunamiyor. 500 MB'lik bir dosyada operator
 * dakikalarca tek sabit satira bakiyordu ve buton acik kaldigi icin ayni dosyayi
 * ikinci kez yuklemeye baslamasi cok kolaydi (ikisi de transcode kuyruguna girer,
 * mini PC'nin CPU'sunu doyurur ve o sirada manifest isteyen otobusler zaman asimina
 * ugrar - yani PENCERE BOSA GIDER). XMLHttpRequest ilerlemeyi verir.
 */
function xhrYukle (url, dosya, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.setRequestHeader('authorization', `Bearer ${TOKEN}`)
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress(e.loaded, e.total) }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try { resolve(JSON.parse(xhr.responseText)) } catch { resolve({}) }
      } else {
        reject(new Error(`${xhr.status} ${xhr.responseText}`))
      }
    }
    xhr.onerror = () => reject(new Error('ağ hatası'))
    xhr.ontimeout = () => reject(new Error('zaman aşımı'))
    xhr.send(dosya)
  })
}

function ilerleme (yuzde, metin) {
  const p = $('ilerleme')
  const t = $('ilerlemeMetin')
  if (p) { p.style.display = yuzde === null ? 'none' : 'inline-block'; if (yuzde !== null) p.value = yuzde }
  if (t) t.textContent = metin || ''
}

async function upload () {
  const f = $('file').files[0]
  if (!f) return log('dosya seçin')
  const btn = $('uploadBtn')
  const kilit = (d) => { if (btn) btn.disabled = d; $('file').disabled = d }
  kilit(true)
  const t0 = Date.now()
  log(`${f.name} yükleniyor (${(f.size / 1e6).toFixed(1)} MB)...`)
  try {
    const r = await xhrYukle(`/api/admin/upload?name=${encodeURIComponent(f.name)}`, f, (yuklenen, toplam) => {
      const yuzde = Math.round((yuklenen / toplam) * 100)
      const gecen = (Date.now() - t0) / 1000
      const hiz = gecen > 0 ? yuklenen / gecen : 0
      const kalan = hiz > 0 ? Math.round((toplam - yuklenen) / hiz) : 0
      ilerleme(yuzde, `${yuzde}% · ${(hiz / 1e6).toFixed(1)} MB/s · ~${kalan} sn kaldı`)
    })
    ilerleme(100, 'sunucuda sıkıştırılıyor (ffmpeg)... bu dakikalar sürebilir')
    const sure = r.item?.durationBilinmiyor ? 'süre BİLİNMİYOR (ffprobe?)' : `${Math.round(r.item.durationMs / 1000)} sn`
    log(`Hazır: ${(r.item.size / 1e6).toFixed(1)} MB, ${r.item.chunkCount} parça, ${sure}`)
    await refresh()
  } catch (e) {
    log('HATA: ' + e.message)
  } finally {
    ilerleme(null, '')
    kilit(false)
  }
}

/** datetime-local -> ISO. Bozuk deger SESSIZ gecmesin. */
function isoTarih (ad, v) {
  if (!v) return null
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) throw new Error(`${ad} tarihi okunamadı: "${v}"`)
  return d.toISOString()
}

async function addCampaign () {
  /*
   * GOVDE TRY ICINDE KURULUR.
   *
   * Eski hali `new Date(...).toISOString()` cagrilarini try blogunun DISINDA
   * yapiyordu; bir RangeError'da Kaydet butonu HICBIR SEY yapmiyor ve log'a tek
   * satir bile dusmuyordu - operator tekrar tekrar basiyordu.
   */
  try {
    const ever = $('cEver').checked

    // Sunucunun ZORUNLU tuttugu alani istemcide de zorla: hata mesajini kullanicinin
    // BAKTIGI yerde gostermek, formu doldurdugu bolumun disindaki bir log kutusuna
    // dusurmekten cok daha iyidir.
    if (!ever && !$('cUntil').value) {
      return kampanyaMesaji('Bitiş tarihi zorunlu: 4G yok, reklam kendi kendine düşmeli.', true)
    }
    if (!$('cItem').value) {
      return kampanyaMesaji('Önce bir içerik yükleyin ve listeden seçin.', true)
    }

    const validFrom = ever ? null : isoTarih('Yayına giriş', $('cFrom').value)
    const validUntil = ever ? null : isoTarih('Bitiş', $('cUntil').value)
    if (validFrom && validUntil && Date.parse(validFrom) >= Date.parse(validUntil)) {
      return kampanyaMesaji('Bitiş, başlangıçtan SONRA olmalı - bu kampanya hiç yayınlanamazdı.', true)
    }
    if (validUntil && Date.parse(validUntil) <= Date.now() &&
        !confirm('Bitiş tarihi GEÇMİŞTE. Bu kampanya hiç yayınlanmaz. Yine de kaydedilsin mi?')) {
      return
    }

    const secili = $('cItem').selectedOptions[0]?.textContent || $('cItem').value
    const body = {
      itemSha: $('cItem').value,
      title: $('cTitle').value.trim() || 'Kampanya',
      advertiser: $('cAdv').value.trim(),
      validFrom,
      validUntil,
      weight: Number($('cWeight').value) || 1,
      groups: $('cGroups').value.split(',').map((x) => x.trim()).filter(Boolean),
      // Saat araligi: sunucu bicimi DOGRULUYOR ve hatali yazimi reddediyor. Cihaz
      // tarafi bozuk bir tanimi "gun boyu" saydigi icin (ekrani karartmamak adina)
      // yazim hatasi baska hicbir yerde gorunmezdi.
      dayparts: $('cDayparts').value.split(',').map((x) => x.trim()).filter(Boolean),
      evergreen: ever
    }
    await api('/api/admin/campaign', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
    // HANGI VIDEO kaydedildigini acikca yaz: yanlis video yayina girdiginde geri
    // alma kanali yok, o yuzden onay satiri dosya adini icermeli.
    kampanyaMesaji(`Kaydedildi: "${body.title}" → ${secili}`)
    log(`Kampanya kaydedildi: ${body.title} → ${secili}`)
    formKirli = false
    for (const id of ['cTitle', 'cAdv', 'cGroups', 'cDayparts']) $(id).value = ''
    await refresh()
  } catch (e) {
    kampanyaMesaji('HATA: ' + e.message, true)
    log('HATA: ' + e.message)
  }
}

async function delCampaign (id) {
  if (!confirm(`${id} silinsin mi?`)) return
  // try/catch: sarmalanmamis bir reddetme tarayicida SESSIZ kalir, operator
  // silmenin basarisiz oldugunu anlamaz ve kampanya yayinda kalmaya devam eder.
  try {
    await api(`/api/admin/campaign/${encodeURIComponent(id)}`, { method: 'DELETE' })
    log(`Kampanya silindi: ${id}`)
    await refresh()
  } catch (e) { log('HATA: ' + e.message) }
}

async function uploadApk () {
  const f = $('apk').files[0]
  if (!f) return log('APK seçin')
  const btn = $('apkBtn')
  if (btn) btn.disabled = true
  const q = new URLSearchParams({ versionCode: $('vCode').value, versionName: $('vName').value, rolloutGroup: $('vRollout').value })
  try {
    const r = await xhrYukle(`/api/admin/app?${q}`, f, (y, t) => ilerleme(Math.round((y / t) * 100), `APK ${Math.round((y / t) * 100)}%`))
    log(`APK yayımlandı: ${r.app.versionName} (rollout ≤ ${r.app.rolloutGroup})`)
    await refresh()
  } catch (e) {
    // 409 = ayni versionCode zaten yayimli. Mesaji anlasilir yapiyoruz.
    log(e.message.startsWith('409') ? `HATA: bu versionCode zaten yayımlı. Yeni bir versionCode kullanın. (${e.message})` : 'HATA: ' + e.message)
  } finally {
    ilerleme(null, '')
    if (btn) btn.disabled = false
  }
}

async function expandRollout () {
  if (!confirm('Güncelleme tüm cihazlara açılsın mı? 48 saat sorunsuz çalıştığından emin olun.')) return
  try {
    await api('/api/admin/app/rollout', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({}) })
    log('Kademeli yayım tüm cihazlara açıldı.')
    await refresh()
  } catch (e) { log('HATA: ' + e.message) }
}

async function temizlik (uygula) {
  if (uygula && !confirm('Kullanılmayan içerik kalıcı olarak silinecek. Emin misiniz?')) return
  try {
    const r = await api('/api/admin/temizlik', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ uygula })
    })
    const mb = (r.kazanilanBayt / 1e6).toFixed(1)
    const yetim = (r.yetimYuklemeler?.length || 0) + (r.yetimIcerikler?.length || 0)
    const ek = yetim ? ` (${yetim} kayıt dışı dosya dahil)` : ''
    $('temizlikSonuc').textContent = r.kuruProva
      ? `${r.silinen} öğe silinebilir${ek}, ${mb} MB kazanılır. Silmek için sağdaki butonu kullanın.`
      : `${r.silinen} öğe silindi${ek}, ${mb} MB kazanıldı.`
    await refresh()
  } catch (e) { $('temizlikSonuc').textContent = 'HATA: ' + e.message }
}

/*
 * RAPOR INDIRME: TOKEN URL'E GIRMEZ VE HATA PANELDE GORUNUR.
 *
 * Eski hali `window.location = '...?token=' + TOKEN` idi. Iki zarari vardi:
 * (a) admin tokeni tarayici gecmisine, Referer'a ve onbellek kutusunun erisim
 *     loguna giriyordu; (b) sunucu 400/401 donerse tarayici panelden CIKIP ham
 *     JSON hata sayfasina gidiyordu - operator geri gelmek icin tokeni yeniden
 *     girmek zorunda kaliyordu.
 */
async function report (hangi = 'report') {
  const q = new URLSearchParams({ from: $('rFrom').value, to: $('rTo').value })
  try {
    const res = await fetch(`/api/admin/${hangi}.csv?${q}`, { headers: { authorization: `Bearer ${TOKEN}` } })
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`)
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${hangi}-${$('rFrom').value || 'tum'}_${$('rTo').value || 'tum'}.csv`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    log(`${hangi}.csv indirildi.`)
  } catch (e) { log('HATA: ' + e.message) }
}

/*
 * SON SAVUNMA HATTI: yakalanmamis hicbir reddetme SESSIZ kalmasin.
 * onclick ile cagrilan async bir fonksiyonda gozden kacan bir hata, aksi halde
 * yalnizca tarayici konsolunda gorunur - ve operator konsola bakmaz.
 */
window.addEventListener('unhandledrejection', (e) => {
  log('BEKLENMEYEN HATA: ' + (e.reason?.message || e.reason))
})
window.addEventListener('error', (e) => {
  log('BEKLENMEYEN HATA: ' + (e.message || 'bilinmiyor'))
})

formIzle()

if (TOKEN) {
  // init() reddi ONCEDEN HIC YAKALANMIYORDU: yanlis token girildiginde panel sifir
  // geri bildirim veriyordu.
  init().catch((e) => {
    log('HATA: ' + e.message)
    refreshBasarisiz(e)
  })
}

setInterval(() => {
  if (!TOKEN) return
  // Form doldurulurken yenileme YAPILMAZ: aksi halde secili video degisir ve
  // kampanya YANLIS videoya baglanir (geri alma kanali yok).
  if (formKirli || formOdakta()) return
  refresh().catch(refreshBasarisiz)
}, 30000)
