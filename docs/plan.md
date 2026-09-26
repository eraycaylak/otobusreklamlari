# Genel Plan — Tek İnternet Hattı + PtMP + Android Stick + Kendi Uygulamamız

> Sabit kısıtlar: otobüs başı 4G **yok**. Tek internet hattı var, PtMP ile 3 toplanma
> noktasına dağıtılacak. Otobüste TV'nin HDMI'sına takılı Android stick var. Oynatma ve veri
> çekme işini **kendi yazdığımız uygulama** yapacak. Senkron penceresi noktada ~3 dakika.

---

## 1. Kısıt altında iş yürür mü? — Evet, ama 3 şart var

| Şart | Neden |
|---|---|
| **1. Sunucuda transcode** | Dosya 50 MB kalırsa 3 dakikaya sığmaz. 2,5 Mbps'e sıkıştır → ~10 MB |
| **2. Her noktada önbellek kutusu** | Tek internet hattı 20 otobüsü besleyemez. Dosya hatta bir kez iner, LAN'dan dağıtılır |
| **3. Parçalı + öncelik sıralı indirme** | Pencere yetmezse kaldığı yerden devam eder; kısmi pencereden bile **tam kullanılabilir dosya** çıkar |

### 1.1 Bant genişliği hesabı — WiFi-only, en kötü senaryo

Otobüs stick'lerinin bir kısmı **2,4 GHz tek bant** olabilir (ucuz stick'lerde yaygın).
2,4 GHz'de bir AP'nin araç gövdesi/cam kaybıyla gerçek toplam kapasitesi ~25 Mbps.

| Senaryo | Toplam veri | Süre | 180 sn'ye sığar? |
|---|---|---|---|
| 1 AP (2,4 GHz, 25 Mbps), 20 otobüs × **50 MB** | 1000 MB | 320 sn | **Hayır** |
| 3 AP (2,4 GHz, ch 1/6/11, ~75 Mbps), 20 otobüs × **50 MB** | 1000 MB | 107 sn | Evet, marj yok |
| 1 AP (25 Mbps), 20 otobüs × **10 MB** | 200 MB | 64 sn | **Evet** |
| 3 AP (~75 Mbps), 20 otobüs × **10 MB** | 200 MB | 21 sn | **Evet, bol marj** |

**Sonuç:** Transcode + 2–3 AP kombinasyonu ile 3 dakika fazlasıyla yeter. Transcode
yapmazsan tek AP kesinlikle yetmez.

### 1.2 Zaman bütçesi (180 saniye nasıl harcanır)

| Adım | Süre |
|---|---|
| WiFi ilişkilendirme + DHCP | 3–8 sn |
| Manifest indirme + imza doğrulama | 1 sn |
| **İçerik indirme** | **~150 sn** |
| Oynatma logu yükleme (gzip, toplu) | 3–5 sn |
| Marj | ~20 sn |

DHCP'yi hızlandır: kısa lease, captive portal **yok**, ek doğrulama **yok**. AP ağı PtMP
üzerinden gerçek internete çıkmalı; aksi halde Android "internet yok" diye ağı geri plana
atabilir.

### 1.3 Yeni otobüs / sıfırdan başlayan cihaz

Kütüphane 20 reklam × 10 MB = 200 MB. Tek otobüsün adil payı ~3,75 Mbps → 200 MB ≈ 7 dakika,
yani **2–3 ziyaret**. Bu normal, parçalı indirme halleder. Ama daha iyisi: yeni stick'i
devreye alırken kütüphaneyi **USB'den veya masada WiFi'den ön-yükle**, sahaya dolu gitsin.

### 1.4 Transcode standardı

```bash
ffmpeg -i girdi.mov \
  -c:v libx264 -profile:v high -level 4.0 -preset slow \
  -b:v 2500k -maxrate 3500k -bufsize 5000k \
  -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,fps=25" \
  -pix_fmt yuv420p -g 50 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -movflags +faststart \
  cikti.mp4
```

Kurallar: **H.264 High profile + yuv420p** (ucuz stick'lerin hepsi donanımda çözer).
**HEVC/H.265 kullanma** — bazı stick'ler çözemez, CPU'ya düşer, kare atlar.
`-movflags +faststart` şart. Tüm dosyalar aynı çözünürlük/fps olmalı ki oynatıcı
geçişlerde takılmasın.

---

## 2. Saha mimarisi

### 2.1 Tek hat, üç nokta → PtMP

Üç noktaya üç ayrı PtP link kurmak yerine, internet olan noktadaki bir direğe **sektör
anten** koy; her toplanma noktasına birer **station** radyo. PtMP'de tüm uzak noktalar
merkezdeki tek internet hattını ve ağ donanımını paylaşır; merkezdeki switch/VLAN ile
noktaları birbirinden de izole edebilirsin.

- Merkez: sektör AP (ör. LiteAP AC 120° sınıfı) — 120° kapsama, airMAX ac CPE'lerle çalışır
- Her nokta: 1 station radyo + PoE + küçük switch + 2–3 AP + önbellek kutusu
- Görüş hattı yoksa: noktalar arası zincirleme relay, ya da o noktaya ayrı ucuz bir hat

### 2.2 Önbellek kutusu — mimarinin kalbi

Her noktada küçük bir mini PC (veya Raspberry Pi 4 + SSD):

- Merkezî sunucunun içerik klasörünü **rsync ile aynalar** (gece/sürekli, yavaş hat sorun değil)
- Otobüslere **nginx** ile servis eder (Range istekleri nginx'te varsayılan olarak çalışır)
- Alternatif: aynalama yerine nginx `proxy_cache` + `slice 1m` ile byte-range önbellekleme.
  Slice modülü tam bu iş için var: büyük, yayınlandıktan sonra değişmeyen video dosyaları.
  **Ama aynalama daha sağlam** — PtMP linki kopsa bile nokta çalışmaya devam eder.
- Manifestleri de aynalar; imza merkezde atıldığı için kutu içeriği değiştiremez

**Kazanç:** 10 MB'lık dosya internet hattından günde bir kez geçer. Nokta ile otobüs
arasındaki trafik tamamen yereldir.

### 2.3 AP yerleşimi

- **En az 2, tercihen 3 AP.** Tek AP'ye 20 araç bindirmeyin.
- 2,4 GHz kanalları **1 / 6 / 11** (örtüşmeyen üçlü). Stick'ler çift bantsa 5 GHz'i de aç
  ve 5 GHz'i tercih ettir.
- Anten yönü otobüslerin park ettiği hatta bakacak; araç gövdesi sinyali ciddi kesiyor,
  AP'yi yüksekte ve otobüs pencerelerini görecek şekilde konumlandır.
- Ayrı VLAN, WPA2-PSK (enterprise değil — el sıkışma süresi uzar), MAC izin listesi.
- SSID tüm noktalarda **aynı** olsun; cihazda tek profil yeter.

---

## 3. Otobüs içi donanım

### 3.1 Güç — 1 numaralı arıza kaynağı

TV'nin USB portu genelde **500 mA** verir, aktif bir stick **900–1200 mA** çeker.
Yetersiz voltaj = rastgele reset + termal kısıtlama. Bu belgelenmiş, yaygın bir sorun.
Üstüne: **TV kapalıyken TV USB'si de kesilir → otobüs noktada dururken stick ölü olur,
senkron hiç yapılmaz.**

**Doğrusu:**
- Otobüs hattından (12/24 V) **araç sınıfı DC-DC dönüştürücü → 5 V / 2 A** ayrı besleme
- 9–36 V giriş aralığı, ters polarite + ani yükselme koruması, **kendi sigortası**
- Kontak kapalıyken de beslenmeli (stick senkron ve log için ayakta kalsın) — ama akü
  boşaltmasın: kontak kapanınca N saat sonra kesen bir zaman rölesi veya düşük voltaj kesici
- Kabloyu kelepçele, konektörde gerilim azaltıcı kullan

### 3.2 Stick seçim kriterleri (satın almadan önce kontrol et)

| Kriter | Neden |
|---|---|
| **ADB erişimi açılabiliyor + Device Owner kurulabiliyor** | Olmazsa proje yürümez. **Fire TV / Fire OS'ta bu genelde mümkün değil — alma.** |
| **Çift bant WiFi (5 GHz)** | 2,4 GHz tek bant, 20 araçlı noktada darboğaz |
| **Ayrı güç girişi (micro-USB/USB-C), HDMI'dan bağımsız** | Bkz. 3.1 |
| ≥ 2 GB RAM, ≥ 16 GB depolama | Uygulama + video kütüphanesi + loglar |
| Android 9+ (AOSP veya Android TV) | Device Owner API'leri, Media3 desteği |
| H.264 High profile 1080p donanım çözücü | Kare atlamaması için |
| Isıya dayanım / havalanan gövde | Yaz ortası otobüs içi 60 °C'yi görebilir |

**Daha iyisi:** stick yerine küçük **Android TV box**. Termalleri iyi, çoğunda Ethernet ve
harici adaptör var, aynı uygulama hiç değişmeden çalışır. Stick de olur; box daha az arıza üretir.

**Yedek al:** %10–15 yedek cihaz. Ucuz stick'ler ölür, sahada beklemek istemezsin.

### 3.3 Kiosk davranışı

- Uygulama **HOME launcher** olarak ayarlı → açılışta doğrudan o gelir
- `BOOT_COMPLETED` alıcısı + foreground service
- **Lock task (kiosk) modu** — Device Owner ile; kullanıcı çıkamaz, durum çubuğu kapalı
- Gece tanımlı saatte kontrollü yeniden başlatma (Device Owner `reboot()`)
- HDMI-CEC ile TV'yi aç / doğru girişe geç (One Touch Play); TV'yi de otel/kiosk moduna al,
  ses seviyesini kilitle
- Cihaz kilitli, erişilemez bir kutuda; dışarıda port/düğme yok

### 3.4 Device Owner provizyonu — atlanamaz adım

Device Owner üç şey için **zorunlu**:

1. **Sessiz uygulama güncellemesi.** Yoksa her app güncellemesinde 50 stick'e fiziksel
   dokunursun — yani USB taşıma probleminin aynısına geri dönersin.
2. **WiFi'ye sessiz bağlanma.** Android 10+ hedefleyen normal uygulamada `addNetwork`
   her zaman **-1** döner; Device Owner / Profile Owner / sistem uygulamaları bu kısıttan muaf.
3. **Kiosk kilidi + reboot + durum çubuğunu kapatma.**

Kurulum, **kutudan yeni çıkmış / fabrika ayarına dönmüş** ve **hiç hesap eklenmemiş**
cihazda yapılır:

```bash
adb install reklam-app.apk
adb shell dpm set-device-owner com.sirket.reklam/.AdminReceiver
```

Cihaza Google hesabı eklendiyse komut başarısız olur → fabrika ayarı gerekir. Bu yüzden
**stick'leri kutudan çıkarınca ilk iş bu olmalı**, hesap açma/güncelleme sonra.

---

## 4. Uygulama ve sunucu

Ayrıntılı spesifikasyon: [`uygulama-spec.md`](uygulama-spec.md)

Özet:

| Katman | Teknoloji | İş |
|---|---|---|
| Otobüs | **Kotlin + Media3/ExoPlayer + Room + OkHttp** | Oynat, senkronla, logla, kendini güncelle |
| Tetikleyici | **`ConnectivityManager.NetworkCallback`** | WiFi görüldüğü an senkron. `PeriodicWorkRequest` **kullanma** — minimum periyodu 15 dakika, 3 dakikalık pencereyi kaçırır |
| Senkron | HTTPS + **Range** istekleri, 4 MB parça, SHA-256 | Kesintide kaldığı yerden devam |
| Nokta | nginx + rsync aynası | LAN hızında servis |
| Merkez | Panel + ffmpeg kuyruğu + manifest imzalama + nginx | Yükleme, planlama, rapor |

---

## 5. 4G olmadığı için kaybettiklerimiz — ve telafisi

Bunlar kısıtın gerçek bedeli. Hiçbirini "sonra bakarız" diye bırakma.

| Kaybettiğimiz | Telafi |
|---|---|
| **Anlık "reklamı kaldır" komutu** | Her reklamda **bitiş tarihi içeriğe gömülü**. Cihaz internetsizken de kendi kendine yayından düşürür. İptal, bir sonraki senkronda yürür. |
| Reklam bitiş tarihi geçtiğinde ne olacak? | **Yayından düşer.** Süresi geçmiş ücretli reklamı oynatmaya devam etmek ticari ve hukuki risk — sözleşme bitmiş envanter yayınlamış olursun. |
| Her şey bitti, senkron da yok → ekran boş mu? | **Hayır.** "Evergreen" yedek set (kendi tanıtımın, kamu spotu, bilgilendirme) her zaman cihazda durur ve asla süresi geçmez. |
| Canlı izleme yok | Panelde "son senkron" zamanı. 72 saat geçen otobüs için otomatik uyarı → tekniker. |
| Oynatma kanıtı gecikmeli | Loglar bir sonraki senkronda gelir. Reklamverene raporun **1–2 gün gecikmeli** olduğunu baştan söyle. |
| Acil uygulama düzeltmesi yavaş yayılır | Uygulama güncellemesi de manifest üzerinden, **kademeli** (önce 2 cihaz, sonra hepsi) gitsin. Bozuk sürümü 50 cihaza aynı gün gönderme. |
| Bir otobüs günlerce noktaya gelmezse | Tekniker telefonu hotspot'u veya dizüstüyle kurulan yedek AP ile zorla senkron. **USB yedek yolunu da açık tut.** |
| Saat kayması | Stick'lerde pil destekli RTC genelde yok. **Her senkronda NTP**, arada monotonik süre takibi. Saat güvenilmezse kural: **şüphede kalırsan süresi geçmiş say ve oynatma.** |

**Şunu Faz 0'da doğrula:** her otobüs gerçekten her gün bu 3 noktadan birine giriyor mu?
Girmiyorsa o otobüs için ayrı bir plan gerekir (ör. garaj/servis noktasına da AP).

---

## 6. Arıza noktaları ve karşılıkları

| # | Risk | Karşılık |
|---|---|---|
| 1 | Stick TV USB'sinden besleniyor → reset / senkron yok | Ayrı 5 V / 2 A araç beslemesi (§3.1) |
| 2 | Device Owner kurulmamış → sessiz güncelleme ve WiFi yok | Cihaz kutudan çıkarken provizyon (§3.4) |
| 3 | Yaz sıcağı / termal kısıtlama | Havalanan montaj, güneş almayan yer, box tercih et |
| 4 | Voltaj sıçraması, takviye akü | 9–36 V araç DC-DC + sigorta + koruma |
| 5 | Uygulama takılması | Foreground service + watchdog timer + gece kontrollü reboot |
| 6 | Tek AP'de 20 araç boğulması | 2–3 AP, ch 1/6/11, 5 GHz varsa tercih, kademeli başlama (rastgele 0–15 sn gecikme) |
| 7 | Pencere yetmedi, dosya yarım | Parçalı indirme + öncelik sıralaması + hash doğrulanmadan oynatmama |
| 8 | Yeni liste bozuk / uygulama çöküyor | 3 açılış denemesinde çökerse **otomatik önceki sürüme dön** |
| 9 | Süresi geçmiş reklam oynamaya devam ediyor | Bitiş tarihi cihazda zorlanır + saat şüphesinde süresi geçmiş say |
| 10 | Ekran boş kalması | Evergreen yedek set + eski dosyayı yenisi doğrulanana kadar silmeme |
| 11 | İçeriğe müdahale | İmzalı manifest (Ed25519), HTTPS, cihaz başına token; imza geçersizse hiçbir şey indirilmez |
| 12 | Önbellek kutusu ölürse | Nokta AP'leri PtMP üzerinden merkeze düşer (yavaş ama çalışır); kutu yedeği rafta dursun |
| 13 | PtMP linki kopması | Aynalama sayesinde nokta mevcut içerikle çalışmaya devam eder |
| 14 | TV kapalı / yanlış giriş / kısık ses | HDMI-CEC ile aç + girişi kilitle + TV kiosk modu + ses kilidi |
| 15 | Şoför cihazı çekmesi | Kilitli kutu, erişilebilir port yok |
| 16 | "Reklamım dönmedi" iddiası | Oynatma kanıtı logu + periyodik ekran görüntüsü |

---

## 7. Yapılmaması gerekenler

1. **Videoyu sıkıştırmadan dağıtmaya çalışma.** Bu kısıt altında tek çıkış yolu transcode.
2. **Tek AP ile 20 otobüse çıkma.** Hesap yukarıda: 50 MB dosyayla 320 saniye ister.
3. **Önbellek kutusunu atlama.** Tek internet hattını 20 otobüse doğrudan açmak demek.
4. **`PeriodicWorkRequest` ile senkron kurma.** 15 dakika minimum periyot, pencereyi kaçırır.
5. **Device Owner'ı "sonra yaparız" deme.** Hesap eklenmiş cihazda fabrika ayarı gerekir.
6. **Fire TV Stick alma.** Fire OS'ta Device Owner pratikte kapalı.
7. **HEVC/H.265 kullanma.** Ucuz stick'lerin bir kısmı donanımda çözmez.
8. **Stick'i TV'nin USB'sine takma.** (Evet, üçüncü kez yazıyorum.)
9. **Süresi geçmiş ücretli reklamı oynatmaya devam etme.** Evergreen'e düş.
10. **50 cihaza aynı gün uygulama güncellemesi gönderme.** Kademeli yay.
11. **Şoföre iş yükleme.** "Şoför şu düğmeye bassın" diye kurulan süreç yürümez.
12. **Elektrik hattına işletmeci onayı olmadan dokunma.**

---

## 8. Maliyet kalemleri

### Tek seferlik — merkez + 3 nokta

| Kalem | Not |
|---|---|
| Sektör AP (merkez, PtMP) + 3 station radyo | airMAX ac sınıfı; uç fiyatları $65–150 bandında |
| Direk, PoE, kablolama, yıldırım koruması | Montaj işçiliği dahil |
| Nokta başına 2–3 AP + switch | Düşük |
| Nokta başına önbellek kutusu (mini PC veya Pi 4 + SSD) | Düşük |
| Merkez yayın sunucusu (mini PC veya VPS) | Düşük |

### Tek seferlik — otobüs başına

| Kalem | Not |
|---|---|
| Android stick/box (çift bant, Device Owner kurulabilir) | Düşük–orta; **%10–15 yedek ekle** |
| Araç sınıfı DC-DC 5 V/2 A + sigorta + kablo + kutu | Düşük |
| Montaj işçiliği | Düşük |
| Ekran değişimi (HDMI yoksa) | Yüksek — Faz 0'da ölç |

### Aylık

| Kalem | Not |
|---|---|
| Tek internet hattı | Zaten var |
| Otobüs başı hat | **0** — 4G yok |
| Yazılım lisansı | **0** — kendi uygulamamız |
| Sunucu (VPS kullanılırsa) | Düşük; on-prem seçilirse 0 |

**Bu mimarinin en güçlü yanı aylık maliyetin neredeyse sıfır olması.** Bedeli §5'teki
kayıplar: anlık kontrol yok, raporlar gecikmeli.

---

## 9. Yol haritası

### Faz 0 — Ölçüm (1 hafta)

- [ ] Stick'in **tam modeli**: WiFi bandı (5 GHz var mı?), RAM, depolama, Android sürümü
- [ ] Bir stick'te **`dpm set-device-owner` denemesi yap** — çalışmıyorsa cihaz değişecek
- [ ] TV'lerin girişi (HDMI var mı?), CEC destekliyor mu
- [ ] Otobüsün güç hattı: voltaj, marş düşüşü, uygun sigorta noktası, kontak besleme durumu
- [ ] Üç noktanın **PtMP görüş hattı** kontrolü (merkez direkten her noktaya)
- [ ] Otobüslerin noktalarda **gerçek duruş süresi** — 1 hafta kayda al
- [ ] **Her otobüs her gün bir noktaya giriyor mu?** — girmeyen varsa listele
- [ ] Mevcut reklam videolarının süre/boyut/bitrate dökümü
- [ ] İşletmeciden montaj + elektrik için yazılı onay

### Faz 1 — Pilot (3–4 hafta, 1 nokta + 2 otobüs)

- [ ] Merkez: yükleme paneli (basit), ffmpeg transcode kuyruğu, manifest imzalama, nginx
- [ ] Nokta 1: station radyo + 2 AP + önbellek kutusu (rsync aynası + nginx)
- [ ] Uygulama v1: kiosk + Media3 kesintisiz oynatma + NetworkCallback senkron + Range
      parçalı indirme + hash doğrulama + atomik geçiş + oynatma logu
- [ ] 2 stick Device Owner provizyonu + ayrı güç beslemesi montajı
- [ ] **Başarı ölçütü:** 2 hafta boyunca hiçbir otobüse elle dokunmadan 3 reklam değişikliği
- [ ] Ölçüm: pencere içinde inen bayt, kesinti sayısı, tamamlanma oranı, çökme sayısı

### Faz 2 — Yaygınlaştırma (4–6 hafta)

- [ ] 3 nokta tamamlanır (sektör anten + 3 station + AP'ler + kutular)
- [ ] Sessiz uygulama güncellemesi (kademeli yayım) devreye alınır
- [ ] İzleme paneli: cihaz başına son senkron, sürüm, disk, sinyal, hata, tamamlanma oranı
- [ ] 72 saat senkron olmayan cihaz için otomatik uyarı
- [ ] Evergreen yedek set + bitiş tarihi zorlaması + saat güvenilirliği
- [ ] Ön-yükleme aracı (yeni cihaz sahaya dolu gider)
- [ ] 10–20 otobüs

### Faz 3 — Ticarileştirme

- [ ] Oynatma kanıtı raporu (CSV/PDF), reklamveren başına
- [ ] Periyodik ekran görüntüsü kanıtı
- [ ] Reklamveren self-servis yükleme + onay akışı
- [ ] Faturalama entegrasyonu
- [ ] Nokta/hat bazlı hedefleme (hangi otobüs hangi listeyi alsın)

---

## 10. Özet

Tek internet hattı + PtMP + 3 dakikalık WiFi penceresi bu iş için **yeterli** — üç şartla:
videoyu sunucuda 2,5 Mbps'e sıkıştır (50 MB → ~10 MB), her toplanma noktasına içeriği
önceden aynalayan bir önbellek kutusu koy, indirmeyi HTTP Range ile parçalı ve öncelik
sıralı yap. Otobüs tarafında kendi Android uygulamanı yaz; ama iki şeyi baştan doğru yap:
stick'i TV'nin USB'sinden değil ayrı 5 V/2 A araç beslemesinden besle, ve cihazı kutudan
çıkardığın an Device Owner olarak provizyonla — yoksa her uygulama güncellemesinde 50
stick'e elle dokunmak zorunda kalır, USB taşıma probleminin aynısına geri dönersin.
4G olmadığı için anlık "reklamı kaldır" komutun olmayacak; bunu bitiş tarihini içeriğe
gömerek ve evergreen yedek set tutarak çöz.
