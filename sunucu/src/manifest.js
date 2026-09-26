import { db } from './store.js'
import { signEnvelope } from './crypto.js'
import { config } from './config.js'

/**
 * Cihaza gonderilen manifest. Kucuk (birkac KB) - pencerenin basinda saniyenin
 * altinda iner, cihaz once bunu alip ne indirecegine karar verir.
 */
export function buildManifest (device) {
  const s = db()
  const group = device.group || 'default'

  const items = []
  for (const c of Object.values(s.campaigns)) {
    if (!c.enabled) continue
    if (!c.evergreen && Array.isArray(c.groups) && c.groups.length && !c.groups.includes(group)) continue
    const item = s.items[c.itemSha]
    if (!item) continue
    items.push({
      id: c.id,
      file: item.file,
      size: item.size,
      sha256: item.sha256,
      durationMs: item.durationMs,
      weight: Math.max(1, Number(c.weight) || 1),
      validFrom: c.evergreen ? null : (c.validFrom || null),
      validUntil: c.evergreen ? null : (c.validUntil || null),
      dayparts: Array.isArray(c.dayparts) ? c.dayparts : [],
      evergreen: !!c.evergreen,
      chunks: item.chunks
    })
  }

  // Evergreen'ler basta dursun: cihaz once guvenlik agini indirsin, ekran asla bos kalmasin.
  items.sort((a, b) => (b.evergreen ? 1 : 0) - (a.evergreen ? 1 : 0))

  const manifest = {
    schema: 1,
    playlistVersion: s.playlistVersion,
    deviceId: device.id,
    deviceGroup: group,
    generatedAt: new Date().toISOString(),
    serverTime: new Date().toISOString(),
    items,
    app: s.app
      ? {
          versionCode: s.app.versionCode,
          versionName: s.app.versionName,
          url: s.app.file,
          size: s.app.size,
          sha256: s.app.sha256,
          rolloutGroup: s.app.rolloutGroup ?? 0,
          critical: !!s.app.critical,
          chunks: s.app.chunks || []
        }
      : null,
    // Cihaz tarafindaki davranis parametreleri: yeniden derlemeden ayarlanabilsin.
    policy: {
      staggerMaxMs: 15000,          // 20 cihaz ayni anda AP'yi bogmasin
      parallelChunks: 2,            // paylasimli AP'de 2 baglanti en verimlisi
      connectTimeoutMs: 8000,
      readTimeoutMs: 15000,
      heartbeatEveryMs: 3600000,
      maxStalenessHours: 72,        // bu sureden uzun senkronsuz kalirsa panelde alarm
      cellularAllowed: false        // 4G yok; ileride acilabilir diye parametre
    },
    rolloutGroups: config.rolloutGroups
  }

  return signEnvelope(manifest)
}
