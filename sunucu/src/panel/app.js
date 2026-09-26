/* eslint-env browser */
const $ = (id) => document.getElementById(id)
let TOKEN = localStorage.getItem('adminToken') || ''
$('token').value = TOKEN

function log (msg) {
  $('log').textContent = `${new Date().toLocaleTimeString('tr-TR')}  ${msg}\n` + $('log').textContent
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

async function refresh () {
  const s = await api('/api/admin/state')
  $('ver').textContent = `liste sürümü: ${s.playlistVersion}`

  $('devices').innerHTML = s.devices.map((d) => {
    const cls = d.revoked ? 'bad' : d.stale ? 'warn' : d.lastSeenAt ? 'ok' : 'warn'
    const ready = d.readyItems != null ? `${d.readyItems}/${d.totalItems}` : '–'
    return `<tr>
      <td><span class="dot ${cls}"></span>${d.revoked ? 'iptal' : d.stale ? 'bayat' : 'iyi'}</td>
      <td>${d.label || d.id}<div class="dim">${d.id}</div></td>
      <td>${d.group}</td>
      <td>${fmtAgo(d.lastSeenAt)}</td>
      <td>${d.playlistVersion ?? '–'}</td>
      <td>${ready}</td>
      <td>${d.appVersion ?? '–'}</td>
      <td>${d.freeBytes != null ? (d.freeBytes / 1e9).toFixed(1) + ' GB' : '–'}</td>
      <td>${d.rssi != null ? d.rssi + ' dBm' : '–'}</td>
      <td>${d.clockTrusted === false ? '<span style="color:var(--bad)">şüpheli</span>' : d.clockTrusted === true ? 'iyi' : '–'}</td>
      <td class="dim">${(d.lastError || '').slice(0, 60)}</td>
    </tr>`
  }).join('')

  $('cItem').innerHTML = s.items.map((i) =>
    `<option value="${i.sha256}">${i.originalName} — ${(i.size / 1e6).toFixed(1)} MB / ${Math.round(i.durationMs / 1000)} sn</option>`
  ).join('')

  $('campaigns').innerHTML = s.campaigns.map((c) => `<tr>
    <td>${c.title}<div class="dim">${c.id}</div></td>
    <td>${c.advertiser || '–'}</td>
    <td>${c.validFrom ? new Date(c.validFrom).toLocaleString('tr-TR') : '–'}</td>
    <td>${c.validUntil ? new Date(c.validUntil).toLocaleString('tr-TR') : '–'}</td>
    <td>${c.weight}</td>
    <td>${(c.groups || []).join(', ') || 'hepsi'}</td>
    <td>${c.evergreen ? 'evergreen' : 'kampanya'}</td>
    <td><button class="sec" onclick="delCampaign('${c.id}')">sil</button></td>
  </tr>`).join('')

  $('appInfo').textContent = s.app
    ? `sürüm ${s.app.versionName} (${s.app.versionCode}) · rollout grubu ≤ ${s.app.rolloutGroup} · ${(s.app.size / 1e6).toFixed(1)} MB`
    : 'henüz APK yayımlanmadı'
}

async function addDevice () {
  try {
    const r = await api('/api/admin/device', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ id: $('dId').value.trim(), label: $('dLabel').value.trim(), group: $('dGroup').value.trim() || 'default' })
  })
    log(`Cihaz eklendi. TOKEN (bir daha gösterilmez): ${r.device.token}`)
    await refresh()
  } catch (e) { log('HATA: ' + e.message) }
}

async function upload () {
  const f = $('file').files[0]
  if (!f) return log('dosya seçin')
  log(`${f.name} yükleniyor (${(f.size / 1e6).toFixed(1)} MB)...`)
  try {
    const r = await api(`/api/admin/upload?name=${encodeURIComponent(f.name)}`, { method: 'POST', body: f })
    log(`Hazır: ${(r.item.size / 1e6).toFixed(1)} MB, ${r.item.chunkCount} parça, ${Math.round(r.item.durationMs / 1000)} sn`)
    await refresh()
  } catch (e) { log('HATA: ' + e.message) }
}

async function addCampaign () {
  const ever = $('cEver').checked
  const body = {
    itemSha: $('cItem').value,
    title: $('cTitle').value.trim() || 'Kampanya',
    advertiser: $('cAdv').value.trim(),
    validFrom: $('cFrom').value ? new Date($('cFrom').value).toISOString() : null,
    validUntil: $('cUntil').value ? new Date($('cUntil').value).toISOString() : null,
    weight: Number($('cWeight').value) || 1,
    groups: $('cGroups').value.split(',').map((x) => x.trim()).filter(Boolean),
    evergreen: ever
  }
  try {
    await api('/api/admin/campaign', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
    log('Kampanya kaydedildi.')
    await refresh()
  } catch (e) { log('HATA: ' + e.message) }
}

async function delCampaign (id) {
  if (!confirm(`${id} silinsin mi?`)) return
  await api(`/api/admin/campaign/${id}`, { method: 'DELETE' })
  await refresh()
}

async function uploadApk () {
  const f = $('apk').files[0]
  if (!f) return log('APK seçin')
  const q = new URLSearchParams({ versionCode: $('vCode').value, versionName: $('vName').value, rolloutGroup: $('vRollout').value })
  try {
    const r = await api(`/api/admin/app?${q}`, { method: 'POST', body: f })
    log(`APK yayımlandı: ${r.app.versionName} (rollout ≤ ${r.app.rolloutGroup})`)
    await refresh()
  } catch (e) { log('HATA: ' + e.message) }
}

async function expandRollout () {
  if (!confirm('Güncelleme tüm cihazlara açılsın mı? 48 saat sorunsuz çalıştığından emin olun.')) return
  await api('/api/admin/app/rollout', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({}) })
  await refresh()
}

async function temizlik (uygula) {
  if (uygula && !confirm('Kullanılmayan içerik kalıcı olarak silinecek. Emin misiniz?')) return
  try {
    const r = await api('/api/admin/temizlik', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ uygula })
    })
    const mb = (r.kazanilanBayt / 1e6).toFixed(1)
    $('temizlikSonuc').textContent = r.kuruProva
      ? `${r.silinen} öğe silinebilir, ${mb} MB kazanılır. Silmek için sağdaki butonu kullanın.`
      : `${r.silinen} öğe silindi, ${mb} MB kazanıldı.`
    await refresh()
  } catch (e) { $('temizlikSonuc').textContent = 'HATA: ' + e.message }
}

function report () {
  const q = new URLSearchParams({ from: $('rFrom').value, to: $('rTo').value, token: TOKEN })
  window.location = `/api/admin/report.csv?${q}`
}

if (TOKEN) init().catch((e) => log('HATA: ' + e.message))
setInterval(() => { if (TOKEN) refresh().catch(() => {}) }, 30000)
