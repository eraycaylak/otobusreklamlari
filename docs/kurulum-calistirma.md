# Kurulum ve Çalıştırma

> Sıfırdan çalışan bir sisteme: merkez sunucu → nokta önbellek kutusu → otobüs cihazı.
> Sırayı bozmayın; her adım bir öncekini doğrular.

---

## 0. Neye ihtiyacınız var

| | |
|---|---|
| Merkez | Linux mini PC veya VPS. Node.js 20+, ffmpeg |
| Nokta | Mini PC / Raspberry Pi 4 + SSD. nginx, rsync |
| Otobüs | Android stick (Android 7+, **device owner kurulabilen**), ayrı 5 V / 2 A besleme |
| Geliştirme | Android Studio (veya JDK 17 + Android SDK), `adb` |

---

## 1. Merkez sunucu

```bash
cd sunucu
npm install
npm run anahtar-uret          # Ed25519 imza anahtarı üretir
```

Çıktıdaki satırı **saklayın**, Android tarafına gerekecek:

```
MANIFEST_PUBLIC_KEY=dDDQzO3DpPzQnzlaUdw1cgJI8w3MOake66FGwloRkPM=
```

> **Özel anahtar (`data/keys/ed25519-private.pem`) sadece merkezde kalır.**
> Git'e girmez (`.gitignore`'da), önbellek kutusuna gitmez, cihaza gitmez.
> Yedekleyin: kaybederseniz tüm cihazların yeniden provizyonu gerekir.

Çalıştırın:

```bash
ADMIN_TOKEN="uzun-rastgele-bir-dize" PORT=8080 npm start
```

Testleri çalıştırın (44 test — Range ile devam eden indirme senaryosu ve güvenlik testleri dahil):

```bash
npm test
```

Panel: `http://sunucu:8080` — admin token'ı girip **Bağlan**.

### Sistem servisi olarak

```bash
cd sunucu
sudo ./scripts/sistem-kurulum.sh
```

Betik gerekli **her şeyi** kurar ve bu bir kolaylık değil zorunluluk: depodaki
`reklam-sunucu.service` üç şeyin var olmasını bekliyor ve hiçbirini kendisi
oluşturmuyor.

| Oluşturulan | Ne için |
|---|---|
| `reklam` sistem kullanıcısı | servis root olarak çalışmasın |
| `/var/lib/reklam` (mod 700) | `DATA_DIR` — **özel imzalama anahtarı** burada |
| `/etc/reklam/sunucu.env` (mod 640) | `ADMIN_TOKEN` (betik üretir ve ekrana basar), `TRUST_PROXY_HOPS`, `TIMEZONE`, `CONTENT_AUTH` |
| imzalama anahtarı | yoksa üretilir; **varsa dokunulmaz** |

> **Unit dosyasını elle kopyalamayın.** Eksik bir `EnvironmentFile` systemd için
> ölümcüldür: servis hiç başlamaz, `Restart=always` yüzünden 5 saniyede bir yeniden
> dener ve `journalctl`'e bakmayan biri için bu "sunucu çalışmıyor, sebebi yok"
> demektir. (Unit artık `StartLimitBurst` ile beş denemeden sonra durup `failed`
> durumunda kalıyor — arıza gizlenmiyor.)

---

## 2. İlk içeriği yükleyin (**evergreen önce**)

İlk yüklediğiniz şey bir **evergreen** içerik olmalı — kendi tanıtımınız, bir kamu
spotu, herhangi bir dolgu. Sebebi: evergreen, ekranın son güvencesidir. Kampanya
yoksa, süresi dolduysa veya saat şüpheliyse ekranı bu doldurur.

```bash
TOKEN="uzun-rastgele-bir-dize"

# 1) Video yükle (sunucu 2,5 Mbps H.264'e çevirir)
curl -H "Authorization: Bearer $TOKEN" --data-binary @tanitim.mp4 \
     "http://localhost:8080/api/admin/upload?name=tanitim.mp4"
# -> {"ok":true,"item":{"sha256":"a1b2...","size":9861234,"chunkCount":3}}

# 2) Evergreen kampanya yap
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"itemSha":"a1b2...","title":"Kurumsal tanıtım","evergreen":true}' \
     http://localhost:8080/api/admin/campaign
```

Normal bir kampanyada **`validUntil` zorunludur** — sunucu tarihsiz kampanyayı
reddeder. 4G olmadığı için uzaktan "kaldır" komutu yok; reklam kendi kendine
yayından düşmek zorunda.

```bash
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"itemSha":"c3d4...","title":"Kahve kampanyası","advertiser":"Kahve A.Ş.",
          "validFrom":"2026-10-01T06:00:00Z","validUntil":"2026-10-15T23:59:59Z",
          "weight":2,"dayparts":["07:00-10:00","17:00-20:00"],"groups":["hat-14"]}' \
     http://localhost:8080/api/admin/campaign
```

> `validFrom`'u kampanya başlangıcından **24–48 saat önceye** verin. Otobüsler
> dosyayı bolca zamanda indirir, hepsi aynı anda yayına geçer.

---

## 3. Nokta önbellek kutusu

```bash
sudo ./onbellek-kutusu/kurulum.sh
sudo nano /etc/nginx/sites-available/reklam   # upstream merkez adresini düzeltin
# SSH anahtarı ROOT için kurulmalı: aynalama cron'da root olarak çalışıyor.
# Kendi kullanıcınıza kurmak sessizce işe yaramaz — cron'daki rsync parola ister,
# bulamaz ve her saat başı başarısız olur (kimse bakmazsa günlerce).
sudo ssh-keygen -t ed25519 -N '' -f /root/.ssh/id_ed25519
sudo ssh-copy-id -i /root/.ssh/id_ed25519 reklam@MERKEZ
sudo -H ssh reklam@MERKEZ true && echo "parolasız erişim TAMAM"
sudo /opt/reklam/ayna.sh                      # ilk aynalama
sudo systemctl reload nginx
```

Aynalama her saat başı (dakika 7) çalışır ve şu dört şeyi garanti eder:

| | Neden |
|---|---|
| **Bütünlük denetimi** | Dosya adları içerik adresli (`ad = sha256`), yani doğrulama yerel. `rsync` dosyaları boyut+tarihe göre atlar; eMMC bozulması veya yarıda kesilen bir yazma yüzünden **doğru boyutta ama bozuk** bir dosya oluşursa rsync onu bir daha hiç getirmez. O noktadaki **her** otobüs indirir, tam dosya hash'i tutmaz, baştan indirir — sonsuza kadar. Hash tutmayan dosya siliniyor ve hemen yeniden çekiliyor. |
| **`--partial-dir`** | `--partial` yarım dosyayı **nihai adıyla** bırakır ve nginx onu tam dosya gibi servis eder. `--append-verify` de kullanılmıyor: o `--inplace`'i ima eder, yani bu önlemi sessizce iptal ederdi. |
| **`--max-delete=50`** | Merkezde `DATA_DIR` değişirse veya disk takılmazsa uzak `content/` **boş** görünür ve düz `--delete` noktanın tüm aynasını siler. Sigorta devreye girerse ayna korunur ve sebep loga yazılır. Gerçekten gerekiyorsa: `MAX_SILME=100000 /opt/reklam/ayna.sh` |
| **`flock`** | Yavaş bir PtMP linkinde aynalama bir saatten uzun sürebilir; iki rsync aynı dosyalara yazarsa tam da önlemeye çalıştığımız bozulmayı üretir. |

> **Manifest önbelleği.** Merkez manifeste bilinçli olarak `Cache-Control: no-store`
> koyuyor (**cihaz** onu önbelleklemesin, bitiş tarihleri taze olsun). nginx de
> varsayılan olarak bu başlığı dinler ve yanıtı önbelleğe **almaz** — bu durumda
> "PtMP koptuğunda bayat manifest servis edilir" sözü **pratikte yoktur** ve bunu
> ancak link gerçekten koptuğunda fark ederdiniz. Bu yüzden `nginx.conf` içinde
> `proxy_ignore_headers Cache-Control` var: başlığı **yalnızca kutu** yok sayıyor,
> cihaza olduğu gibi gidiyor. İkisi ayrı dosyada olduğu için bir sunucu testi bu
> bağı sabitliyor.

> **Erişim listesi.** `/content` ve `/app` yerel aynadan servis edildiği için
> merkezdeki cihaz tokeni zorunluluğu kutuya uygulanamaz (doğrulamak için merkeze
> sormak gerekirdi, ki tam da PtMP kopukken çalışmaz). Sınır ağ katmanında:
> `kurulum.sh` kutunun gerçek alt ağını tespit edip `allow` satırlarına yazar.
> **Bir otobüsün ağından da doğrulayın** — 403 alıyorsanız liste o alt ağı
> kapsamıyor ve hiçbir içerik inmez.

**Kabul testi — bu geçmeden devam etmeyin:**

```bash
curl -sI -r 0-1023 http://localhost/content/a1b2....mp4 | head -3
```

`HTTP/1.1 206 Partial Content` görmeniz **şart**. Göremiyorsanız parçalı indirme
çalışmaz, yani sistemin tamamı çalışmaz.

Link koptuğunda bayat manifest servis edildiğini de doğrulayın:

```bash
curl -s -o /dev/null -D- -H "Authorization: Bearer <cihaz-token>" \
     http://localhost/api/v1/manifest | grep X-Manifest-Cache
```

---

## 4. Android uygulaması

### 4.1 Anahtarları yerleştirin

> **Sırları `android/gradle.properties` içine yazmayın — o dosya depoda takip
> ediliyor.** Gerçek değerleri kullanıcı seviyesindeki dosyaya yazın:
> `~/.gradle/gradle.properties` (Windows: `%USERPROFILE%\.gradle\gradle.properties`).
> Gradle ikisini birleştirir ve kullanıcı dosyası bunu ezer; derleme aynen çalışır
> ama sır depo geçmişine hiç girmez. Aşağıdaki anahtar adları her iki dosyada da
> aynıdır.

`~/.gradle/gradle.properties` (depodaki `android/gradle.properties` yalnızca şablon):

```properties
MANIFEST_PUBLIC_KEY=dDDQzO3DpPzQnzlaUdw1cgJI8w3MOake66FGwloRkPM=
PROVISION_SECRET=uzun-rastgele-bir-dize
```

> `MANIFEST_PUBLIC_KEY` yanlışsa cihaz **hiçbir** manifesti kabul etmez.
> Bu kasıtlı bir davranış: önbellek kutusu ele geçse bile cihaza sahte reklam
> yüklenemez.

### 4.2 İmza anahtarı (release) — **zorunlu**

İmzasız APK cihaza kurulamaz. Anahtar tanımlı değilken release derlemesi artık
anlaşılır bir mesajla **durur** (eskiden sessizce `-unsigned.apk` üretiyordu ve hata
ancak sahada, provizyon sırasında ortaya çıkıyordu).

Kapı `assembleRelease` ile sınırlı değil: `bundleRelease`, `packageRelease` ve
`installRelease` da aynı kontrolden geçer — bunlar da imzalı APK paketler ve
önceden kapıdan sessizce geçiyorlardı.

Aynı kapı `MANIFEST_PUBLIC_KEY` ve `PROVISION_SECRET` değerlerini de doğrular:
boş veya yer tutucu bir değer APK'ya gömülürse cihaz **hiçbir** manifesti
doğrulayamaz (tüm filo sessizce durur) ya da provizyon boş bir sırla kabul edilir.
İkisi de yalnızca sahada anlaşılan hatalardır.

```bash
keytool -genkey -v -keystore reklam.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias reklam
```

Sonra `~/.gradle/gradle.properties` içine (şablonu `android/gradle.properties`
içinde yorumlu olarak hazır — **gerçek parolayı oraya değil, kullanıcı dosyasına**):

```properties
RELEASE_KEYSTORE=/guvenli/yol/reklam.jks
RELEASE_KEYSTORE_PASSWORD=...
RELEASE_KEY_ALIAS=reklam
RELEASE_KEY_PASSWORD=...
```

> **Tüm sürümler aynı anahtarla imzalanmalı.** `PackageInstaller` farklı imzalı bir
> güncellemeyi reddeder — yani sessiz güncelleme çalışmaz ve her otobüse elle gitmek
> gerekir. Anahtarı kaybederseniz geri dönüşü yok: **yedekleyin.**
>
> Anahtar dosyası ve parolalar depoya girmez (`.gitignore` `*.jks` ve `*.keystore`
> dosyalarını dışlıyor).

### 4.3 Test edin ve derleyin

```bash
cd android

# 81 birim testi: saat aralıkları, uygunluk kuralı (validUntil/daypart/evergreen),
# ağırlıklı sıralama, saat mantığı (yeniden başlatma tespiti, imza kademesi, çapa yaşı),
# indirme önceliği ve sunucuyla kademeli yayım hash uyumu
./gradlew testDebugUnitTest

./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

> **`mapping.txt` dosyasını saklayın.** Release derlemesi R8 ile küçültülüyor;
> `app/build/outputs/mapping/release/mapping.txt` olmadan sahadan gelen yığın izleri
> çözülemez. Yayınladığınız **her sürüm için** arşivleyin — CI bunu yapı çıktısı
> olarak da yüklüyor (`r8-mapping`).

> İlk çalıştırmada Gradle kendini indirir (`gradle/wrapper/gradle-wrapper.properties`).
> Ayrı bir Gradle kurulumuna gerek yok.

### 4.4 CI

`.github/workflows/ci.yml` her push'ta sunucu testlerini, Android birim testlerini,
lint'i, debug APK derlemesini ve shellcheck'i çalıştırır; APK'yı artifact olarak
yükler. Yerelde Android SDK kurmak istemiyorsanız APK'yı CI çıktısından indirebilirsiniz.

---

## 5. Cihaz provizyonu

### 5.1 Önce uygunluk kontrolü (50 cihaz almadan önce **bir** tanede)

```bash
./tools/cihaz-kontrol.sh
```

En kritik iki satır:
- **hesap ekli** → device owner atanamaz, fabrika ayarlarına dönmek gerekir
- **zaten bir device owner var** → aynı şekilde

### 5.2 Provizyon

Cihaz **kutudan yeni çıkmış / fabrika ayarında** ve **hiçbir hesap eklenmemiş** olmalı.

```bash
./tools/provizyon.sh OTOBUS-014 \
  --sunucu http://10.20.0.10 \
  --ssid "REKLAM-AP" --psk "ap-parolasi" \
  --api http://127.0.0.1:8080 --admin-token "$TOKEN" \
  --apk android/app/build/outputs/apk/release/app-release.apk \
  --secret "gradle.properties içindeki PROVISION_SECRET" \
  --grup hat-14
```

Çıktıda **`sahip=evet`** ve **`wifi=yazildi`** görmelisiniz. Görmüyorsanız durun —
device owner olmadan sessiz güncelleme ve otomatik WiFi çalışmaz.

### 5.3 Kütüphaneyi ön-yükleyin

Yeni cihazın sahaya boş gitmesine gerek yok: provizyondan sonra kütüphane masada
inebilir. Ayrı bir ön-yükleme aracı yok — normal senkron yolunu kullanmak hem daha
az kod hem de sahada çalıştığı zaten test edilmiş olan yol.

**Ama cihaza yalnızca provizyonda verilen WiFi profili yazılır.** İki yolunuz var:

1. **Masadaki AP'yi noktanın SSID/parolasıyla yayınlayın** (en basit): cihaz onu
   kendi ağı sanar, senkron olur, sahaya takılınca hiçbir şey değişmez.
2. **Ağ bilgisini sonradan güncelleyin** — kimliğe dokunmadan:

```bash
adb shell am broadcast -a com.otobusreklam.player.NETWORK \
  -n com.otobusreklam.player/.provision.ProvisionReceiver \
  --es secret "$PROVISION_SECRET" \
  --es ssid "MASA-AP" --es psk "masa-parolasi"
# -> TAMAM ag guncellendi cihaz=OTOBUS-014 ssid=MASA-AP wifi=yazildi
```

> **Bu yol aynı zamanda bir operasyon sigortasıdır.** AP parolası değişirse
> (personel değişikliği, sızma şüphesi) tüm filo erişilemez hale gelir. Bu yayın
> olmasa tek çıkış yolu **her otobüse gidip fabrika ayarlarına dönmek** olurdu —
> yani rutin bir işletme adımı felakete dönüşürdü.
>
> `deviceId` ve `token` **değişmez**: yalnızca ağ ve sunucu adresi güncellenir.
> Sunucu adresini değiştirmek içerik güvenliğini bozmaz, çünkü manifest Ed25519
> ile imzalı — sahte bir sunucu geçerli manifest üretemez, en fazla hizmet
> kesintisi yapabilir (ki cihaza fiziksel erişimi olan biri bunu zaten yapabilir).

---

## 6. Uygulama güncellemesi (kademeli)

```bash
# Önce SADECE 1. gruba (cihazların ~dörtte biri)
curl -H "Authorization: Bearer $TOKEN" --data-binary @app-release.apk \
  "http://sunucu:8080/api/admin/app?versionCode=42&versionName=1.1.0&rolloutGroup=1"

# 48 saat sorunsuzsa hepsine aç
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  http://sunucu:8080/api/admin/app/rollout
```

Acil bir düzeltmeyse `&critical=1` ekleyin — o zaman güncelleme içerikten **önce**
indirilir.

> **50 cihaza aynı gün güncelleme göndermeyin.**
>
> **Otomatik geri dönüş diye bir şey yok** — ve olamaz: `PackageInstaller` sürüm
> düşürmeyi reddeder (`INSTALL_FAILED_VERSION_DOWNGRADE`), bunu aşan
> `setRequestDowngrade` ise uygulamalara kapalı bir sistem API'sidir. Onun yerine
> uygulama art arda başarısız açılışta **güvenli moda** geçer: sadece oynatma ve
> senkron çalışır, zorunlu olmayan işler atlanır. Böylece ekran kararmaz ve
> düzeltilmiş sürüm ulaşabilir.
>
> Bozuk bir sürüm yayınlarsanız yapılacak şey **ileriye doğru düzeltmektir**:
> daha yüksek bir `versionCode` ile düzeltilmiş APK'yı `&critical=1` ile yayınlayın.
> Panelde o cihazın son hatası `GUVENLI MOD: ...` olarak görünür.
>
> **Saklanan APK ile elle dönüş — dürüst sınır.** `known-good.apk` cihazda
> `/data/data/com.otobusreklam.player/files/app/` altında duruyor ve bu dizini
> **`adb shell` kullanıcısı okuyamaz**: dosya uygulamanın kendi kullanıcısına ait ve
> `run-as` yalnızca `debuggable` derlemelerde çalışır — release APK'da çalışmaz.
> Yani "teknisyen adb ile geri kurar" **gerçekçi bir kurtarma yolu değildir**
> (yalnızca root/kurtarma erişimi olan cihazlarda işe yarar).
>
> Gerçek kurtarma yolu **ileriye doğru düzeltmedir** (yukarıdaki madde). Cihaz hiç
> açılmıyor ve senkron da olamıyorsa kalan tek yol fabrika ayarlarına döndürüp
> yeniden provizyonlamaktır (§5). Kopya yine de tutuluyor: maliyeti bir dosya kadar
> ve root erişimi olan bir vakada işe yarar.

---

## 7. Rapor

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://sunucu:8080/api/admin/report.csv?from=2026-10-01&to=2026-10-15" \
  -o rapor.csv
```

Sütunlar: kampanya, reklamveren, otobüs, gün, oynatma sayısı, toplam saniye,
**saati şüpheli kayıt sayısı**. Son sütun sıfır değilse o cihazın saatinde sorun
var demektir; denetimde tartışma çıkmaması için raporda açıkça gösteriliyor.

Sadece **tamamlanmış** oynatmalar sayılır — yarım kalan oynatma faturalanamaz.

### Pencere raporu — "3 dakika yetiyor mu?"

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://sunucu:8080/api/admin/pencere-raporu.csv?from=2026-10-01&to=2026-10-15" \
  -o pencere.csv
```

Otobüs başına gün bazında: senkron sayısı, toplam inen MB, **ortalama pencere MB**,
en büyük pencere MB, hazır içerik oranı.

Bu rapor projenin temel tasarım sorusunun ölçülmüş cevabıdır. Tüm mimari (transcode,
önbellek kutusu, parçalı indirme) "otobüs 3 dakika duruyor" varsayımına göre kuruldu.
**Pilotu büyütmeden önce bu rapora bakın:** ortalama pencere beklediğinizden küçükse
AP sayısını artırın veya bitrate'i düşürün; hazır içerik oranı 1'e yaklaşmıyorsa
pencere yetmiyor demektir.

---

## 8. Bakım

### Yedekleme — en kritik dosya imzalama anahtarı

`data/keys/ed25519-private.pem` kaybolursa yeni manifest imzalanamaz. Yeni anahtarla
devam etmek, sahadaki **tüm cihazların yeniden provizyonu** (yani her otobüse tek tek
gitmek) demektir.

```bash
cd sunucu
./scripts/yedekle.sh            # anahtarlar + veritabanı + loglar (videolar hariç)
./scripts/yedekle.sh --tam      # videolar da dahil
```

Yedek dosyası özel anahtarı içerir. Dosya **boş olarak ve `chmod 600` ile** oluşturulup
sonra doldurulur; yani hiçbir an okunabilir izinlerle anahtar taşımaz. Betik ayrıca
arşivin **gerçekten anahtarı içerdiğini** doğrular — "yedeğim var" sanıp anahtarsız bir
arşiv tutmak, yedek olmamasından kötüdür, çünkü fark ancak anahtar kaybolunca anlaşılır.

`tar`, okurken değişen bir dosya görürse 1 ile çıkar; bu kurulumda kaçınılmazdır
(oynatma logları append-only NDJSON ve yedek cron'da, cihazlar log yüklerken çalışır).
Bu **uyarı** kabul edilir, `tar`'ın ölümcül hatası (2) kabul edilmez.

**Yedeği sunucunun kendisinde bırakmayın**, başka bir diske veya makineye kopyalayın.
Varsayılan hedef `sunucu/yedekler/` deponun içindedir ve `.gitignore` onu dışlıyor —
aksi halde tek bir `git add -A` tüm filonun imzalama anahtarını depoya sokardı.

Otomatik:

```cron
0 2 * * * /opt/reklam/sunucu/scripts/yedekle.sh >> /var/log/reklam-yedek.log 2>&1
```

### Disk temizliği

Panelden **Disk temizliği** bölümü, veya:

```bash
# Önce ne silineceğini gör (hiçbir şey silinmez)
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'      http://sunucu:8080/api/admin/temizlik

# Gerçekten sil
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'      -d '{"uygula":true,"kanitGun":90}'      http://sunucu:8080/api/admin/temizlik
```

Temizlenenler: hiçbir kampanyada geçmeyen yüklemeler, eski APK sürümleri,
`kanitGun`'den (varsayılan 90) eski kanıt kareleri ve `gecmisGun`'den (varsayılan 180)
eski heartbeat geçmişi. **Oynatma logları silinmez** — onlar faturanın dayanağı.

---

## 9. Sorun giderme

| Belirti | Bakılacak |
|---|---|
| Cihaz panelde hiç görünmedi | Provizyon çıktısında `sahip=evet` var mıydı? WiFi profili yazıldı mı? |
| "IMZA GECERSIZ" hatası | `gradle.properties`'teki `MANIFEST_PUBLIC_KEY`, sunucunun açık anahtarıyla aynı mı? |
| İndirme hiç ilerlemiyor | Önbellek kutusu 206 dönüyor mu? (`curl -r 0-1023 -I`) |
| Cihaz rastgele resetleniyor | Besleme TV'nin USB'sinde mi? Yük altında voltajı ölçün |
| Hep aynı reklam dönüyor | Teşhis ekranını açın (kumandada **2 saniye içinde 3 kez** INFO veya MENU): liste sürümü, son senkron, saat durumu, saat dilimi. Ekran 60 sn sonra kendiliğinden kapanır. |
| Süresi geçmiş reklam oynuyor | Teşhis ekranında "saat: ŞÜPHELİ" mi yazıyor? |
| Kare atlıyor | Dosya transcode standardından mı geçti? HEVC olmamalı |
| Güncelleme gitmiyor | APK'lar **aynı anahtarla** mı imzalı? `rolloutGroup` cihazın grubunu kapsıyor mu? |
| Panelde "GUVENLI MOD" yazıyor | Sürüm açılışta çöküyor. Düzeltilmiş APK'yı `&critical=1` ile yayınlayın |
| Release derlemesi hata veriyor | `~/.gradle/gradle.properties` içinde `RELEASE_KEYSTORE`, `MANIFEST_PUBLIC_KEY` ve `PROVISION_SECRET` tanımlı mı? (§4.2) Kapı `bundleRelease`/`packageRelease`/`installRelease` için de geçerli. |

Cihaz logları:

```bash
adb logcat -s SyncService:* Downloader:* PlayerActivity:* Telemetry:* \
              PlaylistBuilder:* DeviceAdmin:* Updater:*
```
