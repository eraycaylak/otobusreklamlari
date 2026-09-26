# Uygulama Mimarisi (gerçekleşen hali)

> Bu belge **yazılmış kodu** anlatır, tasarım niyetini değil.
> Kurulum için [`kurulum-calistirma.md`](kurulum-calistirma.md), genel mimari için
> [`plan.md`](plan.md).

---

## 1. Bileşenler

| Bileşen | Yol | Teknoloji |
|---|---|---|
| Merkez sunucu + panel | `sunucu/` | Node.js 20+, Express. **Native bağımlılık yok** |
| Nokta önbellek kutusu | `onbellek-kutusu/` | nginx + rsync |
| Otobüs oynatıcı | `android/` | Kotlin, Media3/ExoPlayer, Room, OkHttp |
| Saha araçları | `tools/` | bash + adb |

### Neden veritabanı yok (sunucuda)

50 cihazlık bir filo için JSON dosyası (`data/db.json`, atomik yazma) + append-only
NDJSON log yeterli. Kazanç: `npm install` hiçbir derleme gerektirmez, kurulum
sahada patlamaz, yedekleme `tar` ile biter. Ölçek büyüdüğünde (yüzlerce cihaz,
milyonlarca log satırı) SQLite'a geçin — `store.js` arayüzü aynı kalacak şekilde yazıldı.

Cihaz tarafında ise **Room var**: orada gerçekten eşzamanlı yazma, indeks ve
ani güç kesintisine dayanıklılık gerekiyor.

---

## 2. Manifest ve imza — zarf yaklaşımı

Manifest JSON'unu "kanonik" hale getirip imzalamak klasik bir tuzaktır: anahtar
sırası, boşluk ve unicode kaçışları yüzünden iki taraf asla aynı baytları üretemez.

Bu yüzden sunucu manifest'in **tam baytlarını** base64'leyip zarfa koyar, imza da
tam o baytların üzerine atılır:

```json
{
  "alg": "ed25519",
  "payload": "<base64(manifest json baytları)>",
  "sig": "<base64(64 baytlık ham imza)>"
}
```

Cihaz: payload'ı çöz → **imzayı doğrula** → sonra JSON parse et.

- Sunucu: `sunucu/src/crypto.js` (`signEnvelope`)
- Cihaz: `manifest/SignatureVerifier.kt`
- Kütüphane: `net.i2p.crypto:eddsa` — `java.security` üzerindeki `"Ed25519"`
  sağlayıcısı sadece API 33+'ta var; bu kütüphane API 24'te de aynı çalışır.

**Doğrulandı:** Node'un ürettiği imza, Android'in kullanacağı kütüphaneyle bu
depoda test edilerek doğrulandı — imza geçer, kurcalanmış payload reddedilir,
UTF-8 (Türkçe + emoji) korunur, hex biçimi iki tarafta aynıdır.

**Kural:** imza tutmazsa cihaz **hiçbir şey indirmez**, mevcut listeyi çalmaya
devam eder. Önbellek kutusu ele geçse bile cihaza sahte reklam yüklenemez.

### Manifest içeriği

```json
{
  "schema": 1,
  "playlistVersion": 128,
  "deviceId": "OTOBUS-014",
  "deviceGroup": "hat-14",
  "serverTime": "2026-09-26T08:00:00Z",
  "items": [{
    "id": "kahve-30",
    "file": "content/a1b2c3.mp4",
    "size": 10485760,
    "sha256": "a1b2c3...",
    "durationMs": 30000,
    "weight": 2,
    "validFrom": "2026-10-01T06:00:00Z",
    "validUntil": "2026-10-15T23:59:59Z",
    "dayparts": ["07:00-10:00"],
    "evergreen": false,
    "chunks": [{"i":0,"offset":0,"len":4194304,"sha256":"aa.."}]
  }],
  "app": { "versionCode": 42, "url": "app/reklam-42.apk", "rolloutGroup": 1, "critical": false, "chunks": [] },
  "policy": { "staggerMaxMs": 15000, "parallelChunks": 2, "connectTimeoutMs": 8000, "readTimeoutMs": 15000 }
}
```

`policy` sunucudan gelir: cihaz davranışını **APK yayımlamadan** ayarlayabilirsiniz.

---

## 3. Senkron akışı

`sync/SyncService.kt`

```
WiFi görüldü (NetworkWatcher)
   │
   ├─ 1. MANIFEST — GECİKMESİZ
   │     (~2 KB. 20 cihaz aynı anda çekse 40 KB; AP'yi boğmaz.
   │      Kademeli başlatmayı buradan SONRA yapmak pencereyi boşa harcamaz.)
   │     → Date başlığından saat düzeltmesi
   │     → İMZA DOĞRULAMA (geçmezse buradan geri dönülür)
   │
   ├─ 2. Manifesti diske yaz + oynatıcıyı uyar
   │     (İNDİRMEDEN ÖNCE: süresi dolan reklam dosyası hâlâ diskte olsa bile
   │      anında yayından düşsün.)
   │
   ├─ 3. Kademeli başlatma: rastgele 0–15 sn
   │
   ├─ 4. Öncelik sırasıyla indir
   │     P0  eksik evergreen        → ekranın boş kalma riski her şeyden önce
   │     P1  KRİTİK uygulama güncellemesi
   │     P2  eksik kampanyalar      → validFrom'u en yakın, sonra KALAN BAYTI EN AZ
   │     P3  normal uygulama güncellemesi
   │
   ├─ 5. Oynatıcıyı tekrar uyar
   ├─ 6. Telemetri: loglar → heartbeat → kanıt karesi (pencerenin sonunda)
   └─ 7. Temizlik: artık manifestte olmayan dosyalar, 30 günden eski loglar
```

**P2'deki "kalan baytı en az olan önce" kuralı bilinçli:** yarım 5 dosya yerine
tam 3 dosya çıkarmak daha değerlidir, çünkü yarım dosya oynatılamaz.

### Tetikleme — `PeriodicWorkRequest` kullanılmıyor

`PeriodicWorkRequest`'in minimum periyodu **15 dakika**; 3 dakikalık pencereyi
rahatlıkla kaçırır. Asıl tetikleyici `ConnectivityManager.NetworkCallback`
(`sync/NetworkWatcher.kt`), WiFi ilişkilendirildiği **anda** çalışır.
`SyncWorker` (15 dk) yalnızca emniyet kemeridir.

`NET_CAPABILITY_INTERNET` **aranmıyor** — bilinçli: PtMP linki koptuğunda nokta
ağı "internetsiz" görünür ama önbellek kutusu hâlâ yerel içeriği servis ediyordur.
İnternet şartı koysaydık tam da en çok ihtiyaç duyulan anda senkron hiç başlamazdı.

---

## 4. Parçalı indirme

`sync/Downloader.kt`

- 4 MB parça, her parçanın SHA-256'sı manifestte
- `Range: bytes=<offset>-<offset+len-1>` → sunucu **206 Partial Content** döner
- Her parça inince: hash doğrula → `FileChannel.write(buf, offset)` → `force(false)`
- Parça durumu Room'da; **bağlantı koptuğunda yarım dosya silinmez**
- Tüm parçalar bitince **tam dosya SHA-256** doğrulaması
- Ancak ondan sonra `tmp/<sha>.part` → `content/<sha>.mp4` (atomik `rename`)

**200 kabul edilmez** (tek parçalık dosya hariç): 200 gelmesi sunucunun Range'i
yok saydığı anlamına gelir; o gövdeyi parçanın offset'ine yazmak dosyayı bozardı.

**Paralellik 2:** paylaşımlı bir AP'de tek cihazın çok bağlantı açması *toplam*
verimi düşürür.

### Vazgeçilmez kurallar

1. Yarım dosya asla oynatılmaz (tam inip hash doğrulanmadan listeye girmez)
2. Yarım dosya asla silinmez
3. Eski dosya, yenisi doğrulanana kadar silinmez → ekran kararmaz
4. Geçiş atomiktir
5. Liste değişimi **içerik sınırında** uygulanır, videonun ortasında değil
6. İmza geçersizse hiçbir şey yapılmaz

---

## 5. Depolama düzeni (cihaz)

```
files/
├── content/<sha>.mp4        ← YALNIZCA doğrulanmış dosyalar
├── tmp/<sha>.part           ← yarım inen (parça durumu Room'da)
├── manifest/current.json    ← oynatıcının okuduğu
├── manifest/previous.json   ← geri dönüş
├── app/known-good.apk       ← çalıştığı kanıtlanmış sürüm (temizlikte korunur)
└── reklam.db                ← Room (items, contents, chunks, play_log)
```

---

## 6. Oynatıcı

`player/PlayerActivity.kt`, `player/PlaylistBuilder.kt`

- Media3 ExoPlayer, `REPEAT_MODE_ALL` → döngüde ön-tamponlama, araya siyah kare girmez
- Aktivite aynı zamanda **HOME**: uygulama çökerse Android HOME'u yeniden başlatır —
  işletim sisteminden bedava watchdog
- Kiosk: device owner + `startLockTask()` + `LOCK_TASK_FEATURE_NONE`; tüm tuşlar yutulur
  (kumanda hiçbir şey yapmaz; IR uzatma kablosunu hiç takmamak ek bir katmandır)
- Ağırlık: `weight=2` olan içerik döngüye iki kez girer ama **arka arkaya değil**
- Oynatma hatası: 3. hatada içerik `BAD` işaretlenir, döngüden çıkar, panele bildirilir.
  Yeniden indirmek bunu düzeltmez — çözüm sunucuda yeniden kodlamaktır.
- Watchdog: 30 sn'de bir ilerleme kontrolü → 2. takılmada oynatıcı yeniden kurulur,
  6. takılmada cihaz yeniden başlatılır
- Gece 03:25–03:35 arası kontrollü yeniden başlatma (garajda, yayın saatinde değil)
- Teşhis ekranı: kumandada INFO/MENU → cihaz, sürüm, liste, son senkron, saat durumu,
  device owner durumu

### Liste değişimi neden sınırda?

`setMediaItems` çağrısı oynatıcıyı yeniden hazırlar; bu, içeriğin ortasında yapılırsa
görünür bir kesinti olur. İçerik sınırında yapıldığında kesinti yalnızca hazırlık
süresidir (~100–300 ms siyah), üstelik günde en fazla birkaç kez.

---

## 7. Saat

`clock/ClockManager.kt`

Ucuz stick'lerde pil destekli RTC genelde **yoktur**; güç kesilince saat sıfırlanır.

- Her senkronda sunucunun **`Date` başlığından** gerçek zaman alınır
- Araya geçen süre **monotonik** sayaçla (`elapsedRealtime`) hesaplanır
- Cihaz yeniden başlarsa monotonik çapa sıfırlanır → saat **şüpheli**
- Şüpheliyken kural: **"süresi geçmiş say, oynatma"** → evergreen'e düşülür

### Date başlığı neden gövdedeki `serverTime`'dan öncelikli

Nokta önbelleği (`proxy_cache_use_stale`) PtMP linki koptuğunda **bayat manifest**
servis eder. O manifestin gövdesindeki `serverTime` saatlerce eski olabilir; ona
güvenirsek cihazın saati **geriye gider** ve süresi dolmuş reklamlar yeniden
geçerli hale gelir. `Date` başlığını ise her zaman son atlayan sunucu üretir.

---

## 8. Uygulamanın kendini güncellemesi

`update/Updater.kt` — device owner + `PackageInstaller` → onay ekranı **açılmaz**.

Bu özellik opsiyonel değil: 4G yok, cihazlara noktaya gelmeden erişilemiyor.
Sessiz güncelleme olmazsa her küçük düzeltme için 50 otobüse fiziksel olarak
gitmek gerekir — projenin çözmeye çalıştığı problem tam olarak buydu.

- Kademeli yayım: `rolloutGroup` (cihaz grubu ≤ eşik ise günceller)
- Kurulumdan önce çalışan APK `app/known-good.apk` olarak saklanır
- Açılış sağlık sayacı: açılışta artar, 2 dk sağlıklı çalışınca sıfırlanır;
  3 başarısız açılışta saklanan sürüme dönülür

**Dürüst sınır:** uygulama süreç başlamadan çöküyorsa bu kod hiç çalışmaz.
Gerçek koruma **kademeli yayımdır**.

**Tüm sürümler aynı anahtarla imzalanmalı**, yoksa `PackageInstaller` reddeder.

---

## 9. Oynatma kanıtı

`player/PlaybackLogger.kt`, `telemetry/Telemetry.kt`

- Her oynatma Room'a satır olarak yazılır; `seq` otomatik artar ve kalıcıdır
- Toplu gzip NDJSON olarak gönderilir; **ACK alınmadan satır silinmez**
- Sunucu tekrarları `(cihaz, seq)` ile eler
- `clockTrusted` alanı taşınır: saati şüpheli cihazdan gelen kayıt raporda
  ayrı sütunda gösterilir, denetimde tartışma çıkmaz
- Raporda yalnızca **tamamlanmış** oynatmalar sayılır

### Ekran görüntüsü neden yok — dürüst sınır

Normal bir Android uygulaması ekranın **gerçek görüntüsünü sessizce alamaz**:
`MediaProjection` kullanıcı onay diyaloğu açar (sahada kimse basmaz),
`CAPTURE_VIDEO_OUTPUT` ise imza seviyesi bir izindir. Device owner olmak da
bunu değiştirmez.

Bunun yerine **kanıt karesi** gönderiliyor: oynatılan dosyadan bir kare çıkarılıp
üzerine cihaz kimliği ve zaman damgası yazılıyor (günde en fazla bir kez).

- **Kanıtladığı:** o içeriğin o cihazda o saatte oynatıldığı
- **Kanıtlamadığı:** TV'nin açık olduğu

TV'nin açıklığını yazılımdan güvenilir ölçmek mümkün değil (çoğu TV kapalıyken de
HDMI +5V'u sürer). Reklamverene verilen raporda bu sınır açıkça belirtilmeli;
ekranın gerçekten çalıştığı saha denetimiyle doğrulanır.

### HDMI-CEC de uygulamadan yapılamıyor

`HdmiControlManager` bir sistem API'sidir; uygulama TV'yi açamaz. Pratik çözüm:
CEC'i cihaz ayarlarından açın, TV'yi "son girişe aç" moduna alın ve TV'yi kontak
hattına bağlayın — çoğu otobüste TV zaten kontakla açılıyor.

---

## 10. Device owner — atlanamaz

`admin/DeviceAdmin.kt`

Üç şey buna bağlı:

1. **Sessiz APK güncellemesi** (bkz. §8)
2. **WiFi'ye sessizce bağlanma** — Android 10+ hedefleyen normal uygulamada
   `WifiManager.addNetwork` **her zaman -1 döner**; DO/PO/sistem uygulamaları muaf
3. **Kiosk kilidi, durum çubuğunu kapatma, uzaktan reboot**

```bash
adb shell dpm set-device-owner com.otobusreklam.player/.admin.AdminReceiver
```

Cihaza hesap eklendiyse bu komut başarısız olur → fabrika ayarları gerekir.
Bu yüzden stick'i kutudan çıkarınca **ilk iş** budur.

**ADB bilinçli olarak açılmıyor:** 50 cihazda kalıcı açık ADB, paylaşılan AP
VLAN'ında herkesin cihaza bağlanabilmesi demektir. Servis gerektiğinde teknisyen
ilgili cihazda elle açar.

### Provizyon alıcısının güvenliği

`ProvisionReceiver` exported olmak zorunda (`adb shell` başka bir uygulamadır),
ama iki katmanla korunuyor:

1. Yalnızca cihaz **henüz provizyonlanmamışken** kabul eder
2. `PROVISION_SECRET` eşleşmezse reddeder (sabit zamanlı karşılaştırma)

Provizyon bittikten sonra her yayın sessizce yok sayılır.

---

## 11. Neden düz HTTP

Önbellek kutusu kapalı bir VLAN'da. 50 cihaza sertifika/güven deposu dağıtmanın
işletme yükü büyük; karşılığında kazanılan tek şey **gizlilik** — reklam
videolarının gizli bir yanı yok.

**Bütünlük** ayrı mekanizmayla zaten sağlanıyor: manifest Ed25519 imzalı, her
parça SHA-256 ile doğrulanıyor. Ağda biri araya girse bile cihaza sahte içerik
yükleyemez, yalnızca trafiği izleyebilir.

Merkez sunucu internete açılacaksa HTTPS kullanın ve
`res/xml/network_security_config.xml` içindeki `cleartextTrafficPermitted`'ı
`false` yapın.

---

## 12. Kırma testleri (pilotta bunları bilerek yapın)

- [ ] İndirmenin **tam ortasında** WiFi'yi kesin → sonraki bağlantıda kaldığı yerden devam ediyor mu?
- [ ] İndirme sırasında **fişi çekin** → açılışta veri bozulması var mı, devam ediyor mu?
- [ ] Manifest imzasını **bilerek bozun** (yanlış `MANIFEST_PUBLIC_KEY` ile derleyin) → cihaz reddedip eski listeyi çalmaya devam ediyor mu?
- [ ] Bir videoyu **bozuk gönderin** → o öğe atlanıp döngü devam ediyor mu, `BAD` işaretleniyor mu?
- [ ] Sistem saatini **2 yıl geriye alın** → süresi geçmiş içerik oynamıyor, evergreen'e düşüyor mu?
- [ ] **10 cihazı aynı anda** aynı AP'ye bindirin → kademeli başlatma çalışıyor mu, pencerede kaç MB iniyor?
- [ ] **Bozuk bir APK** yayımlayın (rolloutGroup 1) → sadece o gruba gitti mi, geri dönüş çalıştı mı?
- [ ] **TV'yi kapatın** → stick ayakta mı, senkron oluyor mu? (TV USB'sinden besleniyorsa DEĞİL)
- [ ] **Önbellek kutusunu kapatın** → cihaz mevcut içerikle çalışmaya devam ediyor mu?
- [ ] **PtMP linkini kesin** → kutu bayat manifest servis ediyor mu, saat geriye gitmiyor mu?
- [ ] 72 saat senkron yok → panelde "bayat" uyarısı düştü mü?
