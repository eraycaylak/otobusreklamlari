#!/usr/bin/env node
import { config } from '../src/config.js'

/**
 * Cihaz kaydi ve provizyon komutu uretici.
 *
 *   node scripts/cihaz-ekle.js OTOBUS-014 --grup hat-14 --etiket "34 ABC 123" \
 *        --sunucu http://10.20.0.10:8080
 */
const args = process.argv.slice(2)
const id = args[0]
if (!id || id.startsWith('--')) {
  console.error(
    'Kullanim: node scripts/cihaz-ekle.js <CIHAZ-ID> [--grup g] [--etiket e]\n' +
    '                                      [--sunucu url] [--api url]\n' +
    '                                      [--kanarya N] [--yeni-token]\n' +
    '\n' +
    '  --kanarya N     Kademeli yayim grubu (1 = yeni surumu ILK alan otobus).\n' +
    '                  Verilmezse cihaz id hash ine gore otomatik dagitilir.\n' +
    '  --yeni-token    Mevcut cihazin tokenini YENILER. Var olan bir cihaz icin\n' +
    '                  bu bayrak OLMADAN calistirmak tokeni DEGISTIRMEZ (eskisi\n' +
    '                  yeniden yazdirilir) - bu bilincli: kazara yeniden kayit\n' +
    '                  sahadaki cihazi devre disi birakmasin.\n' +
    '                  DIKKAT: yenilerseniz o otobuse GIDIP yeniden provizyon\n' +
    '                  yapmak gerekir; eski token aninda gecersizdir.'
  )
  process.exit(1)
}
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] ? args[i + 1] : def
}
const bayrak = (name) => args.includes(`--${name}`)

const api = opt('api', `http://127.0.0.1:${config.port}`)
const contentBase = opt('sunucu', api)

const res = await fetch(`${api}/api/admin/device`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', authorization: `Bearer ${config.adminToken}` },
  body: JSON.stringify({
    id,
    group: opt('grup', 'default'),
    label: opt('etiket', id),
    // Sunucu bu bayragi destekliyordu ama betik onu HIC GONDERMIYORDU: cikti
    // "--regenerateToken ile yenileyin" diyor, oysa boyle bir yol yoktu.
    ...(bayrak('yeni-token') ? { regenerateToken: true } : {}),
    ...(opt('kanarya') ? { rolloutGroup: Number(opt('kanarya')) } : {})
  })
})
if (!res.ok) {
  console.error('Kayit basarisiz:', res.status, await res.text())
  process.exit(1)
}
const { device } = await res.json()

console.log(`Cihaz kaydedildi: ${device.id}  (grup: ${device.group}, rolloutGroup: ${device.rolloutGroup})`)
console.log('')
console.log('Provizyon komutu (cihaz KUTUDAN YENI / fabrika ayarinda olmali):')
console.log('')
console.log('  adb install -r reklam-app.apk')
console.log('  adb shell dpm set-device-owner com.otobusreklam.player/.admin.AdminReceiver')
console.log('  adb shell am broadcast -a com.otobusreklam.player.PROVISION \\')
console.log('    -n com.otobusreklam.player/.provision.ProvisionReceiver \\')
console.log(`    --es secret "<PROVISION_SECRET>" \\`)
console.log(`    --es deviceId "${device.id}" \\`)
console.log(`    --es token "${device.token}" \\`)
console.log(`    --es baseUrl "${contentBase}" \\`)
console.log(`    --es apiUrl "${contentBase}" \\`)
console.log(`    --es ssid "<AP-SSID>" --es psk "<AP-PAROLA>"`)
console.log('')
console.log(
  `NOT: bu token cihazin kalici kimligidir. Kaybederseniz --yeni-token ile\n` +
  `     yenileyebilirsiniz, ama o otobuse GIDIP yeniden provizyon yapmak gerekir:\n` +
  `     eski token aninda gecersiz olur ve cihaz bir daha senkron olamaz.`
)
if (device.rolloutPinned) {
  console.log(`     Bu cihaz KANARYA olarak isaretli (rollout grubu ${device.rolloutGroup}):`)
  console.log('     yeni uygulama surumlerini ilk o alir.')
}
