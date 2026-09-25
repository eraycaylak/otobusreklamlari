# Uygulama ve Sunucu Spesifikasyonu

> Otobüsteki Android uygulaması + merkez sunucu + nokta önbellek kutusu.
> Kodlamaya başlamadan önce bu dosyayı okuyun; buradaki kuralların çoğu sahada
> bir kez yanıldığınızda geri dönüşü pahalı olan kurallar.

---

## 1. Bileşenler

| Bileşen | Nerede | Teknoloji |
|---|---|---|
| **Yayın paneli + API** | Merkez (ofis mini PC veya VPS) | Node.js/TS veya Python FastAPI + PostgreSQL |
| **Transcode kuyruğu** | Merkez | ffmpeg + iş kuyruğu |
| **İçerik servisi** | Merkez | nginx (Range desteği varsayılan) |
| **Önbellek kutusu** | Her toplanma noktası | Mini PC / Pi 4 + SSD, nginx + rsync + log kuyruğu |
| **Oynatıcı uygulama** | Otobüs Android stick | Kotlin, Media3/ExoPlayer, Room, OkHttp, WorkManager (tek seferlik) |

---

## 2. Manifest — cihazın çektiği tek küçük dosya (~2–5 KB)

```json
{
  "schema": 1,
  "playlist_version": 128,
  "device_group": "hat-14",
  "generated_at": "2026-09-25T08:00:00Z",
  "server_time": "2026-09-25T08:00:00Z",
  "app": {
    "version_code": 42,
    "url": "app/reklam-42.apk",
    "size": 8912896,
    "sha256": "f00d...",
    "rollout_group": 1,
    "critical": false
  },
  "items": [
    {
      "id": "kahve_kampanya_30",
      "file": "content/a1b2c3d4e5f6.mp4",
      "size": 10485760,
      "sha256": "a1b2c3...",
      "duration_ms": 30000,
      "weight": 2,
      "valid_from": "2026-09-26T06:00:00Z",
      "valid_until": "2026-10-10T23:59:59Z",
      "dayparts": ["07:00-10:00", "17:00-20:00"],
      "evergreen": false,
      "chunks": [
        {"i": 0, "offset": 0,       "len": 4194304, "sha256": "aa.."},
        {"i": 1, "offset": 4194304, "len": 4194304, "sha256": "bb.."},
        {"i": 2, "offset": 8388608, "len": 2097152, "sha256": "cc.."}
      ]
    },
    {
      "id": "kurumsal_tanitim",
      "file": "content/9f9f9f9f9f9f.mp4",
      "size": 6291456,
      "sha256": "9f9f...",
      "duration_ms": 20000,
      "weight": 1,
      "valid_from": null,
      "valid_until": null,
      "evergreen": true,
      "chunks": [ "..." ]
    }
  ],
  "signature": "ed25519:...."
}
```

### Kurallar

- **İçerik adresli dosya adı** (`a1b2c3d4e5f6.mp4` = SHA-256 önekı). Aynı dosya iki kez inmez,
  önbellek bayatlamaz, "hangi sürüm?" sorusu ortadan kalkar.
- **`signature`**: manifest Ed25519 ile merkezde imzalanır, açık anahtar uygulamaya gömülüdür.
  **İmza doğrulanmazsa hiçbir şey indirilmez, mevcut liste aynen çalmaya devam eder.**
  Önbellek kutusu içeriği değiştirse bile cihaz kabul etmez.
- **`valid_from` / `valid_until`**: cihazda **çevrimdışı** zorlanır. Kampanyayı başlangıçtan
  24–48 saat önce yayına it, `valid_from` ile hepsi aynı anda dönmeye başlasın.
- **`evergreen: true`**: süresi asla dolmaz, cihazdan asla silinmez. Her şey biterse
  ekranı bu doldurur. En az 2–3 evergreen içerik olsun.
- **`weight`**: döngüde kaç kez geçeceği (2 = iki kat sık).
- **`rollout_group`**: uygulama güncellemesinin kademeli yayımı için. Cihazın grubu
  manifestteki gruptan küçük/eşitse güncellemeyi alır.
- **`server_time`**: saat senkronu için (§7).

---

## 3. Senkron akışı — 180 saniyeyi nasıl harcıyoruz

### 3.1 Tetikleme — `PeriodicWorkRequest` KULLANMA

`PeriodicWorkRequest`'in minimum periyodu **15 dakika**. 3 dakikalık pencere kaçar.

Doğrusu:

```kotlin
// Uygulama açılışında bir kez kaydedilir, uygulama ömrü boyunca açık kalır.
val req = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()

connectivityManager.registerNetworkCallback(req, object : NetworkCallback() {
    override fun onAvailable(network: Network) {
        // SSID bizim ağımız mı? Evet ise gecikmeden tetikle.
        SyncService.startNow(context, network)      // foreground service
    }
    override fun onLost(network: Network) {
        SyncService.pause(context)                 // durumu diske yaz, yarım dosyayı SİLME
    }
})
```

- **Foreground service** kullan (bildirim gizli/minimal). Yoksa sistem işi kesebilir.
- Yedek olarak 15 dakikalık bir `PeriodicWorkRequest` dursun — ama **ana tetikleyici
  NetworkCallback**. Periyodik iş sadece "kaçırılmış bir şey var mı" kontrolü yapar.
- Foreground service içinde tek seferlik `OneTimeWorkRequest` (expedited) ile indirme
  çalıştır; böylece süreç ölse bile iş devam eder.

### 3.2 Senkron durum makinesi

```
BEKLEME
  └─ WiFi(bizim SSID) görüldü ──► KADEMELI_BEKLE (rastgele 0–15 sn)
                                    │  (20 cihaz aynı milisaniyede AP'yi boğmasın)
                                    ▼
                                 SAAT_SENKRON  (sunucu yanıtındaki Date/server_time)
                                    ▼
                                 MANIFEST  (GET, ~2 KB, imza doğrula)
                                    │ imza geçersiz ──► ABORT (mevcut liste devam eder)
                                    ▼
                                 PLANLA  (eksik parçaları ve ÖNCELİĞİ hesapla)
                                    ▼
                                 INDIR  ◄──┐  her parça: Range isteği + SHA-256 doğrula
                                    │      │  durumu her parçada diske yaz (fsync)
                                    │      └── hâlâ parça var ve WiFi ayakta
                                    │
                        WiFi koptu ─┴──► DURAKLAT (yarım dosya KORUNUR) ──► BEKLEME
                                    ▼
                                 DOGRULA  (tam dosya SHA-256)
                                    ▼
                                 ATOMIK_GECIS  (manifest/current.json rename)
                                    ▼
                                 LOG_YUKLE  (gzip NDJSON, ACK'e kadar sakla)
                                    ▼
                                 TEMIZLIK  (artık hiçbir manifestte olmayan dosyaları sil)
                                    ▼
                                 BEKLEME
```

### 3.3 Öncelik sıralaması — kısmi pencereden azami fayda

Pencere yetmeyebilir. O yüzden **hangi baytı önce çekeceğin** kritik. Sıra:

| P | Ne | Neden |
|---|---|---|
| **P0** | Cihazda hiç evergreen yoksa evergreen içerik | Ekranın boş kalma riski her şeyden önce gelir |
| **P1** | `critical: true` uygulama güncellemesi | Bozuk sürümü düzeltmek her şeyden acil |
| **P2** | `valid_from`'u en yakın olan, **tamamlanmamış** içerikler — aralarında **kalan baytı en az olan önce** | En çok sayıda **tam** dosya çıkarır. Yarım 5 dosya yerine tam 3 dosya. |
| **P3** | Normal uygulama güncellemesi | |
| **P4** | Geri kalan içerik | |

```kotlin
// P2 sıralaması: yayına giriş tarihi, sonra kalan bayt (artan)
val plan = items
    .filter { !it.isComplete }
    .sortedWith(compareBy({ it.validFrom ?: Instant.MAX }, { it.remainingBytes }))
```

### 3.4 İndirme parametreleri

| Parametre | Değer | Neden |
|---|---|---|
| Paralel bağlantı | **2** | AP paylaşımlı; 4+ bağlantı toplam verimi düşürür |
| Parça boyutu | **4 MB** | Kayıp iş küçük, manifest şişmez |
| Bağlantı zaman aşımı | 8 sn | Ölü ağda beklemeyelim |
| Okuma zaman aşımı | 15 sn | |
| Yeniden deneme | Sınırsız, 3 sn bekleme | Pencere bitince zaten duracak |
| Range başlığı | `Range: bytes=<offset>-<offset+len-1>` | 206 Partial Content |
| Doğrulama | Her parçada SHA-256, sonunda tüm dosya | |

```kotlin
val request = Request.Builder()
    .url(baseUrl + item.file)
    .header("Range", "bytes=${chunk.offset}-${chunk.offset + chunk.len - 1}")
    .header("Authorization", "Bearer $deviceToken")
    .build()
// 206 dışında bir kod dönerse parçayı atla, durumu bozma.
```

### 3.5 Vazgeçilmez kurallar

1. **Yarım dosya asla oynatılmaz.** Tam inip SHA-256 doğrulanmadan listeye girmez.
2. **Yarım dosya asla silinmez.** Bağlantı koptuğunda `tmp/<sha>.part` ve parça durumu kalır.
3. **Eski dosya, yenisi doğrulanana kadar silinmez.** Ekran hiçbir koşulda kararmaz.
4. **Geçiş atomik.** `manifest/next.json` yaz → `fsync` → `rename` → `current.json`.
5. **Geçiş içerik sınırında olur**, videonun ortasında liste değiştirilmez.
6. **İmza geçersizse hiçbir şey yapılmaz**, mevcut durum korunur.

---

## 4. Depolama düzeni

```
files/
├── content/
│   ├── a1b2c3d4e5f6.mp4        ← yalnızca DOĞRULANMIŞ dosyalar
│   └── 9f9f9f9f9f9f.mp4
├── tmp/
│   └── a1b2c3d4e5f6.part       ← yarım indirilen (parça durumu DB'de)
├── manifest/
│   ├── current.json            ← oynatıcının okuduğu
│   └── previous.json           ← geri dönüş için
├── app/
│   └── reklam-41.apk           ← çalışan sürümün APK'sı (geri dönüş için)
└── state.db                    ← Room
```

**Disk bütçesi:** 16 GB stick'te uygulama + sistem sonrası ~8–10 GB kalır.
20 reklam × 10 MB = 200 MB. Rahat. Ama temizliği yine de yaz: hiçbir manifestte
(current + previous) olmayan dosya silinir. Evergreen'ler asla silinmez.

### Room tabloları (asgari)

| Tablo | Alanlar |
|---|---|
| `items` | id, sha256, file, size, duration_ms, weight, valid_from, valid_until, dayparts, evergreen, state(MISSING/PARTIAL/READY/BAD) |
| `chunks` | item_sha256, index, offset, len, sha256, done |
| `play_log` | seq (autoincrement), item_id, content_sha256, started_at, duration_ms, completed, playlist_version, screen_on, uploaded |
| `device_state` | device_id, token, playlist_version, last_sync_at, last_known_time, clock_trusted, app_version |

`play_log.seq` **monotonik ve kalıcı** olmalı — sunucu tekrarları `(device_id, seq)` ile ayıklar.

---

## 5. Oynatıcı

- **Media3 / ExoPlayer.** `setMediaItems(...)` + `REPEAT_MODE_ALL`, ya da
  `ConcatenatingMediaSource` — kaynaklar arası geçiş sorunsuz, araya siyah kare girmez;
  ön-tamponlama (pre-buffering) devrede kalır.
- Tüm videolar **aynı çözünürlük, aynı fps, aynı codec** olmalı. Karışık olursa geçişte
  decoder yeniden kurulur ve siyah kare görürsün. Transcode standardı bunu garanti eder.
- `SurfaceView` (TextureView değil), tam ekran, hiçbir UI öğesi yok, `keepScreenOn`.
- **Liste değişimi içerik sınırında**: `onMediaItemTransition` içinde bekleyen yeni liste
  varsa uygula.
- **Bozuk dosya döngüyü öldürmez:** decode hatasında öğeyi `BAD` işaretle, atla, loga yaz,
  bir sonraki senkronda tekrar indirmeyi dene.
- **Daypart/geçerlilik filtresi** liste kurulurken uygulanır; saat başı yeniden değerlendir.
- Ses: varsayılan **kapalı/kısık** (işletmeci politikası). Yapılandırılabilir olsun.

### Watchdog

```
AlarmManager her 60 sn:
  - player.currentPosition ilerledi mi?
  - hayır ve oynuyor olması gerekiyor → player'ı yeniden kur
  - 3 denemede düzelmezse → DevicePolicyManager.reboot()
Her gece 03:30 (garajda) → kontrollü reboot
```

---

## 6. Uygulamanın kendini güncellemesi (Device Owner)

**Bu olmadan proje yürümez.** Yoksa her uygulama düzeltmesinde 50 stick'e fiziksel
dokunmak zorunda kalırsın — yani USB taşıma probleminin aynısına dönersin.

```
manifest.app okunur
  → cihazın rollout_group'u uygun mu?
  → APK indir (Range ile, parçalı — büyük dosya)
  → SHA-256 doğrula
  → PackageInstaller session (Device Owner → onay ekranı YOK, sessiz kurulum)
  → kurulum sonrası uygulama yeniden başlar
  → ilk başarılı senkronda "sürüm sağlıklı" işaretlenir
```

**Güvenlik ağı:**
- Çalışan sürümün APK'sı `files/app/` altında saklanır.
- Yeni sürüm açılışta 3 kez çökerse veya 2 senkron üst üste başarısız olursa
  saklanan APK geri kurulur.
- **Kademeli yayım zorunlu:** `rollout_group` ile önce 2 cihaz, 48 saat sorun yoksa hepsi.
  50 cihaza aynı gün gönderme.

---

## 7. Saat — sessiz ama ölümcül konu

Ucuz stick'lerde pil destekli RTC genelde **yoktur**. Güç kesilince saat sıfırlanır.
`valid_until` zorlaması saate bağlı olduğu için bu ciddi bir konu.

**Kurallar:**

1. Her senkronda saati düzelt: sunucu yanıtının `Date` başlığı veya manifestteki
   `server_time`. Ayrı NTP portu açmaya gerek yok.
2. `last_known_time` + `SystemClock.elapsedRealtime()` çapası diske yazılır; arada geçen
   süre monotonik sayaçtan hesaplanır.
3. Açılışta sistem saati `last_known_time`'dan **geriye** gidiyorsa saat **güvenilmez** sayılır.
4. **Şüphede kalırsan süresi geçmiş say.** Güvenilmez saatte:
   - `valid_until` geçmiş olabilecek her içerik **oynatılmaz**
   - `valid_from` gelecekte olabilecek içerik **başlatılmaz**
   - **evergreen** içerik oynatılır (süresi hiç dolmaz)
5. Log kayıtlarına `clock_trusted` alanı eklenir; rapor üretirken güvenilmez saatli
   kayıtlar işaretlenir.

**Neden önemli:** süresi bitmiş ücretli bir reklamı oynatmaya devam etmek, sözleşmesi
bitmiş envanter yayınlamak demek. İptal edilmiş bir reklam için de aynısı geçerli.
Bu ticari ve hukuki risk — kod tarafında sert kural olarak dursun.

---

## 8. Sunucu API'si

| Yöntem | Yol | İş |
|---|---|---|
| `GET` | `/api/v1/manifest?device=<id>` | Cihaz grubuna göre imzalı manifest |
| `GET` | `/content/<sha>.mp4` | İçerik (Range zorunlu) |
| `GET` | `/app/reklam-<v>.apk` | Uygulama APK'sı (Range) |
| `POST` | `/api/v1/logs` | gzip NDJSON oynatma logu → en yüksek ACK'lenen `seq` döner |
| `POST` | `/api/v1/heartbeat` | Sürüm, disk, sinyal, sıcaklık, son hata, tamamlanma oranı |
| `POST` | `/api/v1/screenshot` | Periyodik ekran görüntüsü (haftada 2–3) |

**Kimlik:** cihaz başına `Bearer` token, provizyonda yazılır. Token iptal edilebilir olsun.

**Oynatma logu satırı (NDJSON):**

```json
{"seq":12345,"item_id":"kahve_kampanya_30","sha256":"a1b2c3...","started_at":"2026-09-26T08:14:02Z","duration_ms":30000,"completed":true,"playlist_version":128,"screen_on":true,"clock_trusted":true}
```

Sunucu `(device_id, seq)` ile tekrarları ayıklar. Cihaz, ACK gelene kadar logu **silmez**.

---

## 9. Önbellek kutusu (her nokta)

```nginx
# Aynalama yaklaşımı (ÖNERİLEN): içerik rsync ile yerelde, PtMP kopsa bile çalışır
server {
    listen 80;
    root /srv/reklam;
    location /content/ { }            # Range nginx'te varsayılan olarak çalışır
    location /app/     { }
    location /api/     { proxy_pass http://merkez; }   # log/heartbeat ileri taşır
}
```

```bash
# cron: gece + her saat
rsync -a --delete merkez:/srv/reklam/content/ /srv/reklam/content/
rsync -a          merkez:/srv/reklam/manifest/ /srv/reklam/manifest/
```

**Alternatif:** aynalama yerine `proxy_cache` + `slice 1m`. nginx'in slice modülü tam bu
iş için var (büyük, yayınlandıktan sonra değişmeyen video dosyaları); `$slice_range`
mutlaka `proxy_cache_key`'e girmeli. **Ama aynalama daha sağlam** — link koptuğunda nokta
kendi başına ayakta kalır.

**Log kuyruğu:** kutu, otobüsten aldığı logları diske alıp merkeze sonra iletsin
(store-and-forward). PtMP koptuğunda log kaybı olmaz.

---

## 10. Provizyon (her stick için, sırayla)

```bash
# 1) Cihaz KUTUDAN YENİ / fabrika ayarında, HİÇ HESAP EKLENMEMİŞ olmalı
adb devices

# 2) Uygulamayı kur
adb install reklam-app.apk

# 3) Device Owner yap  (hesap eklendiyse BU KOMUT BAŞARISIZ OLUR → fabrika ayarı)
adb shell dpm set-device-owner com.sirket.reklam/.AdminReceiver

# 4) Cihaz kimliği + token + sunucu adresi + WiFi bilgisi yaz
adb shell am broadcast -a com.sirket.reklam.PROVISION \
  --es device_id "OTOBUS-014" --es token "..." --es base_url "http://nokta.local" \
  --es ssid "..." --es psk "..."

# 5) Kütüphaneyi ön-yükle (sahaya dolu gitsin)
adb push content/ /sdcard/reklam-preload/

# 6) Doğrula: kiosk açıldı mı, WiFi'ye kendi bağlanıyor mu, oynatma başladı mı
```

**Provizyon kontrol listesi (her cihaz):**

- [ ] Device Owner başarılı (`dpm` komutu OK döndü)
- [ ] Uygulama HOME launcher, açılışta otomatik geliyor
- [ ] Kiosk (lock task) aktif, durum çubuğu kapalı
- [ ] WiFi profili kayıtlı, cihaz ağa **kendi** bağlanıyor
- [ ] Ayrı 5 V / 2 A besleme bağlı — **TV USB'sinden DEĞİL**
- [ ] HDMI-CEC ile TV açılıyor, doğru girişe geçiyor
- [ ] Kütüphane ön-yüklü, evergreen içerik oynuyor
- [ ] Cihaz panelde görünüyor, heartbeat geliyor
- [ ] Cihaz kilitli kutuda, dışarıda erişilebilir port yok

---

## 11. Uygulama modül yapısı (öneri)

```
app/
├── player/        Media3 kurulumu, liste oluşturma, daypart filtresi, watchdog
├── sync/          NetworkCallback, SyncService, indirme motoru (Range + parça + hash)
├── manifest/      JSON model, Ed25519 imza doğrulama, atomik geçiş
├── store/         Room (items, chunks, play_log, device_state), dosya yönetimi
├── clock/         saat senkronu, güvenilirlik kararı
├── update/        APK indirme + PackageInstaller (Device Owner sessiz kurulum)
├── admin/         DeviceAdminReceiver, lock task, reboot, WiFi profili
├── telemetry/     heartbeat, log paketleme (gzip NDJSON), ekran görüntüsü
└── provision/     provizyon broadcast alıcısı, ön-yükleme içe alma
```

---

## 12. Test edilmesi gerekenler (pilotta bunları bilerek kır)

- [ ] İndirmenin **tam ortasında** WiFi'yi kes → sonraki bağlantıda kaldığı yerden devam ediyor mu?
- [ ] Cihazın **fişini çek** (indirme sırasında) → açılışta veri bozulması var mı, devam ediyor mu?
- [ ] Manifest imzasını **bilerek boz** → cihaz reddediyor ve eski listeyi çalmaya devam ediyor mu?
- [ ] Bir videoyu **bozuk gönder** → o öğe atlanıp döngü devam ediyor mu?
- [ ] Sistem saatini **2 yıl geriye al** → süresi geçmiş içerik oynamıyor, evergreen'e düşüyor mu?
- [ ] 10 cihazı **aynı anda** aynı AP'ye bindir → kademeli başlama çalışıyor mu, kaç MB iniyor?
- [ ] **Bozuk bir APK** yayımla (rollout_group 1) → geri dönüş çalışıyor mu?
- [ ] TV'yi kapat → stick ayakta mı, senkron oluyor mu, log tutuyor mu?
- [ ] Önbellek kutusunu kapat → cihaz mevcut içerikle çalışmaya devam ediyor mu?
- [ ] 72 saat senkron yok → panelde uyarı düştü mü?
