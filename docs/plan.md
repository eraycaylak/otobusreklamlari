# Otobüs İçi Reklam Yayın Sistemi — Ayrıntılı Teknik Plan

> Bu belge: mevcut durumun teşhisi, hesaplar, tüm bağlantı seçenekleri, parçalı indirme
> protokolü, donanım seçimi, arıza noktaları, maliyet kalemleri ve **yapılamayacaklar**.

---

## 1. Mevcut durum ve hedef

**Şimdi:** Reklam gelince USB bellek sökülüyor → bilgisayara takılıyor → dosya kopyalanıyor →
otobüse götürülüp geri takılıyor. Her reklam değişikliği = otobüs sayısı kadar fiziksel iş.
Hata payı yüksek, kim ne oynattı belli değil, reklamverene kanıt sunulamıyor.

**Hedef:** Tek panelden yükle → otobüsler kendi indirsin → sırayla oynatsın → ne oynattığını
geri bildirsin. İnsan eli sadece arıza durumunda devreye girsin.

**Kritik tasarım ilkesi:** Sistem *"bağlantı her an kopar"* varsayımıyla yazılacak.
Bağlantı bir lüks, yerel depolama ise ana kaynak. Ekran internet olmadan haftalarca
son doğrulanmış listeyi oynatmaya devam etmeli.

---

## 2. Hesap: darboğaz gerçekten nerede?

### 2.1 Tek otobüs, tek dosya

| Dosya | Süre | Gerekli sürekli hız |
|---|---|---|
| 50 MB | 180 sn (3 dk) | **2,23 Mbps** |
| 50 MB | 60 sn | 6,7 Mbps |
| 10 MB | 180 sn | 0,45 Mbps |

50 MB = 400 megabit. 400 / 180 ≈ 2,2 Mbps. Türkiye'de 4G tipik indirme hızı 10–50 Mbps
bandında; yani **50 MB tek otobüse 8–40 saniyede iner**. "3 dakikada inmez" korkusu tek
otobüs için yersiz.

### 2.2 Asıl problem: kalabalık

20 otobüs aynı anda **tek** erişim noktasına bağlanırsa, o AP'nin gerçek toplam kapasitesi
(2,4 GHz, araç gövdesi, cam kaybı) ~30–40 Mbps'e düşer:

| Senaryo | Toplam veri | 35 Mbps'de süre | 3 dk'ya sığar mı? |
|---|---|---|---|
| 20 otobüs × 50 MB | 1000 MB | ~229 sn | **Hayır** |
| 20 otobüs × 10 MB | 200 MB | ~46 sn | **Evet, rahat** |
| 40 otobüs × 10 MB | 400 MB | ~92 sn | Evet |

**Çıkarım:** Problem "3 dakika" değil, **dosya boyutu × araç sayısı**.

### 2.3 En büyük ve en ucuz kazanç: sunucu tarafında yeniden sıkıştırma

Otobüsteki ekran 18–22", izleme mesafesi 2–5 metre. Bu ekranda 8–10 Mbps ile 2,5 Mbps
arasındaki farkı kimse göremez. Reklamveren ne verirse versin, sunucu **tek bir standarda**
çevirmeli:

```bash
ffmpeg -i girdi.mov \
  -c:v libx264 -profile:v high -level 4.0 -preset slow \
  -b:v 2500k -maxrate 3500k -bufsize 5000k \
  -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,fps=25" \
  -pix_fmt yuv420p \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -movflags +faststart \
  cikti.mp4
```

Sonuç: **30 saniyelik reklam ≈ 9–11 MB** (50 MB değil). Aynı dosya hem 4G'de 3 saniyede
iner, hem 20 otobüs tek AP'den 1 dakikada çeker, hem aylık veri maliyetini 5'e böler.
Sıkıştırmayı bir kez sunucuda yap, 50 otobüs bedavaya kazansın.

Not: Sektör 1080p dijital tabela için genelde 6–10 Mbps önerir; o öneri büyük mağaza
ekranları ve statik-detaylı içerik içindir. Küçük araç ekranı için 2,5–4 Mbps yeterlidir —
pilotta gözle doğrulanacak, ekrana bakıp karar verilecek.

### 2.4 Aylık veri tüketimi

| Kalem | Miktar |
|---|---|
| 20 reklam değişikliği × 10 MB | 200 MB |
| Telemetri + oynatma logu + heartbeat (MQTT) | 20–50 MB |
| **Otobüs başı toplam** | **~250 MB/ay** |
| 50 otobüs için toplam | ~12,5 GB/ay |

Araç takip cihazları ayda ~50 MB kullanıyor ve 1 GB'lık M2M paketleri fazlasıyla yeterli
sayılıyor; bizim ihtiyacımız onun 5 katı ama hâlâ küçük kotalı paket sınıfında.

---

## 3. Mimari

```
[ Reklam yükleme paneli ]            (web arayüzü: video yükle, otobüs/hat seç, tarih ver)
            │
            ▼
[ Transcode + parçalama servisi ]    (tek standarda çevir, 4 MB parçalara böl, SHA-256 al)
            │
            ▼
[ Dosya deposu + CDN / imzalı URL ]  (S3 uyumlu depo; Range isteklerini desteklemek ZORUNLU)
            │
            ├──────── MQTT kontrol kanalı (küçük, sürekli, anlık komut) ────────┐
            │                                                                   │
            ▼                                                                   ▼
[ Otobüs oynatıcısı ]  ←── 4G (ana) ──┐                        [ İzleme paneli ]
  - manifest çeker                    ├── Alan WiFi (bedava hızlandırıcı)   - hangi otobüs
  - parçaları indirir, doğrular       └── Yerel önbellek sunucusu             hangi sürümde
  - atomik geçiş yapar                                                       - son görülme
  - oynatma logu tutar                                                       - oynatma kanıtı
  - HDMI → TV
```

### Üç ayrı kanal, üç ayrı iş

1. **Kontrol kanalı (MQTT, kalıcı, aylık birkaç MB):** "yeni sürüm var", "şu reklamı
   ACİL kaldır", "yeniden başlat", "ekran görüntüsü gönder". Anlık ve neredeyse bedava.
2. **Veri kanalı (HTTPS + Range, kesintili):** ağır video indirme. Kesilir, devam eder.
3. **Rapor kanalı (toplu, sıkıştırılmış):** oynatma logları günde 1–2 kez paketlenip gider.

Bu ayrım önemli: reklamveren "reklamımı bugün kaldır" dediğinde 10 MB indirmeyi beklemene
gerek kalmaz; 200 baytlık bir MQTT mesajı işi bitirir.

---

## 4. Bağlantı seçeneklerinin tamamı — tek tek

### A) Otobüste 4G/LTE modem + M2M SIM — **ANA YOL**

**Nasıl:** Oynatıcının içinde dahili LTE modem (araç sınıfı Android oynatıcılar genelde
dahili sunar) veya ayrı endüstriyel router (ör. Teltonika RUT200/RUT241 sınıfı; ~$102'den
başlıyor, endüstriyel, filo araçlarında kullanılıyor, dijital giriş/çıkışı var — kontak
sinyali için kullanılabilir).

| Artı | Eksi |
|---|---|
| Toplanma alanına bağımlı değil; dosya yolda, trafikte, garajda iner | Otobüs başı aylık hat ücreti |
| Anlık komut kanalı (acil reklam kaldırma, uzaktan teşhis) | Tünel/kapsama boşluğunda kesilir (→ resume çözer) |
| Kurulum basit: cihaz + SIM, altyapı yatırımı yok | SIM yönetimi (50 hat = 50 abonelik takibi) |
| GPS ile konumlu oynatma kanıtı üretilebilir | |

**Maliyet notu:** Türkiye'de Turkcell / Vodafone / Türk Telekom / Netgsm M2M-IoT hatları
sunuyor. **Güncel TL fiyatlarını bu oturumda doğrulayamadım** (operatör siteleri ağ
politikası nedeniyle açılmadı) — 50 hat için doğrudan kurumsal teklif alınmalı. Bilinen
referanslar: Vodafone 1 GB'lık M2M paketlerinde aşım 0,07 TL/MB; 12–24 ay taahhüt aylık
ücreti düşürüyor. Bizim ihtiyaç 250 MB/ay olduğundan **500 MB–1 GB paketi** hedeflenmeli.

### B) Toplanma alanında WiFi + yerel önbellek sunucusu — **İKİNCİ KATMAN**

Otobüsler günde belirli dakikalar tek noktada duruyor — bu bedava bant genişliği, kullanmamak
israf. Ama tek başına yeterli değil.

**Kurulum:**
- Alana **2–3 erişim noktası**, mutlaka **5 GHz** destekli, kanalları ayrı (1/6/11 ve
  UNII bantları). Tek AP'ye 20 araç binmesin.
- Alanda **küçük bir önbellek sunucusu** (mini PC / NUC, 500 GB SSD). Kritik nokta:
  dosya internetten **bir kez** iner, sonra 20 otobüse **LAN hızında** dağıtılır. Alanın
  internet hattı 10 Mbps bile olsa sorun olmaz.
- Otobüs WiFi'ye girince oynatıcı **kademeli başlar** (0–20 sn rastgele gecikme) ki 20 cihaz
  aynı milisaniyede AP'yi boğmasın.
- Güvenlik: ayrı VLAN, cihaz başına ayrı PSK veya WPA2-Enterprise, MAC izin listesi.

| Artı | Eksi |
|---|---|
| Veri maliyeti pratikte sıfır | Otobüs o gün alana gelmezse içerik eskir |
| Yüksek hız, büyük dosya derdi yok | Alanda AP + sunucu + elektrik + internet gerekiyor |
| 4G kotasını korur | Anlık komut kanalı yok (otobüs uzaktayken erişemezsin) |

**Bonus:** Otobüs alana *girerken kapıdan geçerken* bile 10–20 saniyelik bağlantı yakalar.
Parçalı indirme sayesinde bu 10 saniye bile boşa gitmez.

### C) Point-to-Point kablosuz köprü — alanda internet yoksa

Yakındaki fiber/ADSL'i olan bir binadan alana kablosuz köprü ile internet taşınır.
Ubiquiti LiteBeam AC Gen2 sınıfı cihaz: 450 Mbps, 23 dBi, 15+ km menzil, **uç başına
~$65–100** (iki uç gerekir).

| Artı | Eksi |
|---|---|
| Alana bol bant genişliği, düşük gecikme | **Net görüş hattı şart** (ağaç/bina engel) |
| Tek seferlik donanım maliyeti, aylık yok | Direk, montaj, elektrik, yıldırım koruması, izin |
| 1 hat ile tüm filoyu besler | Kurulum uzmanlık ister |

### D) Alanda 4G/5G CPE router — C'nin kolay alternatifi, **ÖNCE BUNU DENE**

Alana SIM'li bir CPE router + AP. Görüş hattı yok, direk yok, izin yok; 1 saatte kurulur.
CPE router'lar masaüstü/dış mekân sınıfında düzinelerce eşzamanlı istemciyi taşıyabiliyor.
Yerel önbellek sunucusuyla birleşince 20 otobüs için tek SIM yeterli olur.

**Karar sırası: D → yetmezse C.** PtP'yi ancak alanın kapsaması kötüyse veya çok veri
gerekiyorsa kur.

### E) "Akıllı USB" — Pi Zero USB mass-storage gadget

**TV'de HDMI girişi yoksa, sadece USB oynatıcı varsa** kurtarıcı çözüm. Raspberry Pi Zero W,
USB-OTG ile TV'ye **USB bellek gibi** görünür (`modprobe g_mass_storage file=/piusb.bin`),
içeriğini WiFi/4G üzerinden kendi günceller. TV'ye hiç dokunmadan sistemi uzaktan
yönetilebilir hale getirir.

**Dikkat:** TV yeni dosyayı görmesi için USB'nin "çıkarılıp takılması" gerekir — bu,
`rmmod g_mass_storage` / tekrar `modprobe` ile taklit edilir. **Pi ile TV aynı yazılabilir
dosya sistemini aynı anda bağlamamalı**, yoksa dosya sistemi bozulur. Akış şu olmalı:
gadget'ı kaldır → imajı güncelle → senkronize et → gadget'ı geri tak.

| Artı | Eksi |
|---|---|
| TV/ekran değiştirmeye gerek yok, en düşük yatırım | Kırılgan; TV'nin USB davranışına bağımlı |
| Mevcut kurulumun aynısı, sadece bellek "akıllı" | Oynatma kanıtı, zamanlama, rapor üretemez |
| | 50 araçta yönetmesi zahmetli |

Yani: **geçici köprü çözümü**. Kalıcı mimari HDMI'lı oynatıcı olmalı.

### F) Otobüsler arası P2P dağıtım — Faz 3

Alanda bir otobüs 4G'den indirir, diğerlerine WiFi/BitTorrent benzeri yerel eşler üzerinden
dağıtır. Veri maliyetini araç sayısına böler.

| Artı | Eksi |
|---|---|
| 4G veri maliyeti ciddi düşer | Karmaşıklık yüksek, hata ayıklaması zor |
| Alan internetine bağımlılık azalır | 50+ araç ve büyük dosya olmadan getirisi yok |

Yerel önbellek sunucusu (B) aynı faydayı %10 karmaşıklıkla verir. **P2P'yi şimdilik yazma.**

### G) Şoför telefonu hotspot — sadece acil kurtarma

Bedava ama şoförün iyi niyetine, bataryasına ve kotasına bağlı. Kalıcı çözüm olamaz.
Tek kullanımı: bir otobüs uzun süre güncellenemediyse teknikerin telefonuyla eşleyip
zorla güncelleme.

### H) Elden USB — yedek olarak KALSIN

Sistem tamamen oturana kadar (ve oturduktan sonra da) manuel USB yolu silinmemeli.
Oynatıcı, takılan USB'de geçerli imzalı bir paket görürse onu kabul etmeli. Bir otobüs
3 gün internete çıkamadıysa tek çare bu olur.

### Değerlendirilip elenen yollar

| Yol | Neden olmaz |
|---|---|
| LoRa / Sigfox / NB-IoT (düşük bant) | kbps seviyesinde; video taşımaz. Sadece telemetri için anlamlı. |
| Uydu (Starlink vb.) | Araç başı maliyet ve montaj, bu iş için absürt. |
| Canlı yayın / streaming | Kapsama boşluğunda ekran kararır, veri maliyeti katlanır. **Her şey indir-sonra-oynat olmalı.** |
| Bluetooth ile toplu dağıtım | Menzil ve hız yetersiz, eşleşme yönetimi kâbus. |
| TV'nin kendi "akıllı" özellikleri | Araç TV'lerinin çoğunda ağ yok; olanlarda da uygulama yönetimi imkânsız. |

---

## 5. Parçalı indirme protokolü — "20 MB indi, kalan 30 MB sonraki durakta"

Bu, çözülmüş bir mühendislik problemi. Temel: **HTTP Range** isteği ve **206 Partial
Content** yanıtı. İstemci "şu bayttan sonrasını ver" der, sunucu oradan devam eder.
Hazır oynatıcılar da bunu yapıyor (Xibo istemcisi kesilen indirmeleri kaldığı yerden
sürdürüyor; özellikle kesintili/yavaş bağlantılar için eklenmiş bir davranış).

### 5.1 Manifest (otobüsün çektiği tek küçük dosya, ~2 KB)

```json
{
  "playlist_version": 128,
  "generated_at": "2026-09-25T08:00:00Z",
  "valid_from": "2026-09-26T06:00:00Z",
  "items": [
    {
      "id": "reklam_kahve_30sn",
      "file": "a1b2c3d4e5f6.mp4",
      "size": 10485760,
      "sha256": "a1b2c3...",
      "duration_sec": 30,
      "weight": 2,
      "daypart": ["07:00-10:00", "17:00-20:00"],
      "chunks": [
        {"index": 0, "offset": 0,       "len": 4194304, "sha256": "aa.."},
        {"index": 1, "offset": 4194304, "len": 4194304, "sha256": "bb.."},
        {"index": 2, "offset": 8388608, "len": 2097152, "sha256": "cc.."}
      ]
    }
  ],
  "signature": "ed25519:...."
}
```

- **İçerik adresli isim** (`a1b2c3d4e5f6.mp4` = dosyanın hash'i): aynı dosya iki kez inmez,
  önbellek asla bayatlamaz, sürüm karışması olmaz.
- **`valid_from`**: kampanya başlangıcından 24–48 saat önce dosyayı it, otobüsler bolca
  zamanda indirsin, hepsi aynı anda yayına geçsin.
- **`signature`**: manifest imzalı. İmza doğrulanmazsa hiçbir şey indirilmez/oynatılmaz.
  Böylece kimse araya girip otobüse istediği videoyu sokamaz.

### 5.2 Oynatıcının indirme döngüsü

```
her 60 sn (ve ağ bulunca hemen):
  1. manifest çek (HTTPS, cihaz token'ı ile) → imzayı doğrula
  2. eksik parçaları hesapla (yerel durum dosyasından)
  3. ağ tipini öğren:
       WiFi  → sınırsız indir, 4 paralel bağlantı
       4G    → paralel 1-2, dosya > 30 MB ise "acil" işaretli değilse WiFi'yi bekle
  4. eksik parçaları Range isteğiyle indir:
       Range: bytes=4194304-8388607
     her parça inince SHA-256 doğrula, durumu diske yaz (fsync)
  5. bağlantı koparsa: yarım dosyayı SİLME, durumu koru, çık
  6. tüm parçalar tamamsa: bütün dosyanın SHA-256'sını doğrula
  7. doğrulandıysa → playlist.json'u ATOMİK değiştir (yeni dosyaya yaz + rename)
  8. eski sürümü SAKLA (son 2 sürüm), sonra temizle
```

Komut satırı karşılığı (prototip için yeterli):

```bash
aria2c -c -x2 -s2 --file-allocation=none \
       --check-integrity=true --checksum=sha-256=a1b2c3... \
       --max-tries=0 --retry-wait=5 \
       -d /var/lib/reklam/indirme -o a1b2c3d4e5f6.mp4 \
       "https://cdn.../a1b2c3d4e5f6.mp4"
```

`-c` kaldığı yerden devam eder; `.aria2` kontrol dosyası parça haritasını ve doğrulama
bilgisini tutar. İndirme %100 bitince kontrol dosyası kendiliğinden silinir. Süreç
öldürülse, cihaz kapansa, ağ gitse — aynı komut tekrar çalıştığında kaldığı yerden devam eder.

### 5.3 Vazgeçilmez kurallar

1. **Yarım dosya asla oynatılmaz.** Tam inip hash doğrulanmadan listeye girmez.
2. **Eski içerik, yenisi doğrulanana kadar silinmez.** Ekran hiçbir koşulda boş kalmaz.
3. **Geçiş atomiktir.** Yarı güncellenmiş liste diye bir durum olmaz.
4. **Geri dönüş var.** Yeni liste açılışta 3 kez çökerse otomatik önceki sürüme dönülür.
5. **Saat güvenilir olmalı.** Zamanlama (daypart) internet yokken de çalışmalı → donanım
   RTC veya GPS saati. İnternetsiz cihazın saati kayarsa yanlış saatte yanlış reklam oynar,
   bu da faturalanabilir bir hatadır.

---

## 6. Otobüs içi donanım

### 6.1 Oynatıcı seçenekleri

| Seçenek | Yaklaşık maliyet | Değerlendirme |
|---|---|---|
| Ucuz Android TV box (X96/H96 vb.) | Çok düşük | **Önerilmez.** Isı, titreşim, 220V adaptör, kalitesiz eMMC, rastgele yazılım. Pilotta olur, filoda dert olur. |
| Raspberry Pi 4/5 + araç DC-DC + endüstriyel SD/SSD | Orta | Tam kontrol, büyük topluluk, Yodeck/PiSignage/Anthias doğrudan destekler. Güç ve kart yönetimi doğru yapılmalı. |
| **Araç sınıfı Android oynatıcı (dahili 4G + GPS)** | Orta | **Önerilen.** 9–36 V giriş, kontak (ignition) girişi, geniş sıcaklık aralığı, dahili LTE/WiFi/GPS. Bu iş için tasarlanmış sınıf. |
| Entegre "otobüs reklam ekranı" (ekran + oynatıcı bir arada) | Yüksek | Ekranları da yenileyeceksen mantıklı. 12–24 V, 15,6"–22"–32" seçenekleri var; Türkiye'de tedarikçi mevcut (Erpa Teknoloji, DIOS, Microkey). |
| Endüstriyel fanless mini PC | Yüksek | Aşırı kaçar, gereksiz. |

**Ekranın girişini önce ölç.** HDMI varsa mesele yok. Sadece USB/SD okuyan bir "medya
oynatıcı TV" ise ya ekranı değiştir ya da E seçeneğine (akıllı USB) geç.

### 6.2 Güç — en çok arıza buradan çıkar

- Otobüs hattı 24 V nominal ama marş anında düşer, alternatör ani yükseltir, takviye
  aküde 30 V üstü görülür. **9–36 V girişli, ters polarite ve ani yükselme korumalı
  araç sınıfı DC-DC** kullan. Doğrudan hatta lehimleme yok.
- **Kendi sigortanı koy** (uygun amperde, hattın başına).
- **Kontak (ignition) girişi kullan:** kontak kapanınca cihaz *hemen* kesilmesin;
  30–60 saniye çalışıp logları yazsın, düzgün kapansın (süper kapasitör veya küçük batarya).
  Bu, kart bozulmalarının büyük kısmını önler.
- Kabloları gergin bırakma, titreşim için kelepçele, konektörlerde gerilim azaltıcı kullan.

### 6.3 Depolama ve dosya sistemi — ikinci büyük arıza kaynağı

Ani güç kesintisi sırasında yazma işlemi **SD kart bozulmasının ana sebebi**; tek dosyayı
da bozabilir, kartı tümden kullanılamaz da yapabilir. Önlemler:

- **eMMC veya endüstriyel (pSLC/SLC) kart** kullan; tüketici microSD kullanma.
- **Kök dosya sistemi salt-okunur + overlayfs.** Yazılabilir alan yalnızca içerik ve log
  bölümü olsun. (Dikkat: overlayfs kök dosya sistemini her açılışta geri alır; indirilen
  içerik **ayrı kalıcı bölümde** durmalı.)
- Logları RAM'e al (`log2ram` benzeri), düzgün kapanışta diske yaz.
- `fstab`'da `noatime`; günlük işleme aralığını uzat (`commit=300`).
- Aylık kalan disk ve kart sağlığı raporu gönder.

### 6.4 Yazılım dayanıklılığı

- **Donanım watchdog** aktif; oynatıcı takılırsa cihaz kendini resetler.
- `systemd` ile `Restart=always`, çökme sayacı ve geri dönüş mantığı.
- Gece tanımlı saatte kontrollü yeniden başlatma (garajdayken, yayın saatinde değil).
- Ekran yönetimi: HDMI-CEC ile TV'yi aç, doğru girişe geç; TV'yi kiosk/otel moduna al
  (kanal/giriş değiştirilemesin, ses kilitli).
- Cihazı **erişilemez bir kutuya** koy: dışarıda USB portu, açık düğme olmasın.

---

## 7. Yazılım: hazır CMS mi, kendi sistemin mi?

### Hazır çözümler

| Ürün | Model | Fiyat (kaynaklardan) | Not |
|---|---|---|---|
| **Xibo** | Açık kaynak, kendin barındır / bulut | Kendin barındır: **ücretsiz**; bulut **$4,90/ekran/ay**'dan | Kesilen indirmeyi kaldığı yerden sürdürür — bizim senaryonun tam karşılığı. Linux/Windows oynatıcı ücretsiz; Android/webOS oynatıcıda cihaz başı tek seferlik lisans var (tutar teyit edilmeli). **Pilot için en iyi başlangıç.** |
| **Yodeck** | Bulut, Raspberry Pi tabanlı | **$8 / $12 / $16** ekran/ay | Çevrimdışı oynatma var, kurulumu en kolay. Ekran çoğaldıkça aylık maliyet birikiyor. |
| **PiSignage** | Bulut + Pi | **$8–12** ekran/ay | Çevrimdışı çalışmaya odaklı. |
| Anthias (eski Screenly OSE) | Açık kaynak | Ücretsiz | Basit; zamanlama ve raporlama zayıf. |

**Pilotta hazır çözüm kullan.** Xibo self-hosted ile sıfır lisans maliyetiyle başla; 2 otobüste
"elimi USB'ye sürmedim" noktasına 2–3 haftada gel.

### Kendi sistemini yazmak (Faz 2)

Hazır CMS'lerin senin işinde yetmeyeceği yerler:

1. **Reklamveren raporu ve faturalama** — "X markası, 14–20 Temmuz, 32 otobüs, 4.812 oynatma".
   Hiçbir hazır ürün senin fatura formatını bilmiyor.
2. **Hat/güzergâh bazlı hedefleme** — GPS'e göre "bu reklam sadece 14 numaralı hatta".
3. **Otobüs filosu gerçekleri** — kontak, kota yönetimi, alan WiFi'si, kademeli indirme.
4. **Reklamveren self-servis paneli** — müşteri kendi videosunu yüklesin, onaya düşsün.

Sıra şu olmalı: hazır CMS ile yayına gir → süreç otursun → ne eksik olduğunu *gerçek
veriyle* öğren → kendi panelini yaz. Tersi sırayla 3 ay kaybedilir.

### Önerilen teknoloji yığını (kendi sistemini yazarken)

| Katman | Öneri | Neden |
|---|---|---|
| Panel + API | Node.js/TypeScript veya Python (FastAPI) | Hızlı, ekip bulunur |
| Veritabanı | PostgreSQL | Kampanya, oynatma logu, cihaz durumu |
| Dosya deposu | S3 uyumlu (MinIO veya bulut) + imzalı URL | **Range desteği zorunlu** |
| Transcode | ffmpeg + iş kuyruğu | Tek standarda çevirme |
| Kontrol kanalı | MQTT (Mosquitto/EMQX) | Küçük, kalıcı, anlık komut |
| Oynatıcı tarafı | Android (Kotlin, WorkManager + ExoPlayer) veya Linux (Python + mpv) | Kontak/ağ olaylarına göre iş planlama |
| İzleme | Grafana + basit heartbeat tablosu | "Hangi otobüs kaç gündür güncellenmedi?" |

---

## 8. Oynatma kanıtı (proof-of-play) — para kazandıran kısım

Bu, sistemin reklamvereni ikna eden yüzü. Standart kayıt şunları taşır: içerik kimliği,
kampanya adı, kesin zaman damgası, ekran kimliği, konum/grup, oynatma süresi ve dönem
içindeki oynatma sayısı. Bu kayıtlar reklamveren faturalaması ve denetim için CSV olarak
dışa verilir.

**Bizim eklememiz gerekenler:**
- **GPS konumu** — "reklamınız şu hatlarda, şu bölgelerde döndü".
- **Ekran açıklık doğrulaması** — TV kapalıysa oynatma sayılmamalı (HDMI-CEC durumu veya
  panel akımı okunarak).
- **Dönemsel ekran görüntüsü** — haftada birkaç kez oynatıcıdan kare gönder; şüpheye yer bırakmaz.
- **Kurcalanamaz log** — cihazda imzalı, sıralı (sequence numaralı) kayıt; sunucuda tekrar
  doğrulanır. İleride üçüncü taraf denetimi istenirse hazır olursun.
- **Saat güvenilirliği** — NTP + RTC/GPS. Yanlış zaman damgası tüm raporu çöpe atar.

Loglar günde 1–2 kez sıkıştırılıp toplu gönderilmeli (sürekli akış veri yakar).

---

## 9. Arıza noktaları ve karşılıkları

| # | Risk | Karşılık |
|---|---|---|
| 1 | Güç kesintisinde kart bozulması | Endüstriyel eMMC, salt-okunur kök + overlayfs, kontak girişiyle düzgün kapanma, log2ram |
| 2 | Yaz sıcağı (araç içi 60–70 °C'yi görebilir) | Geniş sıcaklık aralıklı cihaz, güneş almayan montaj, fansız + havalanan kutu |
| 3 | Titreşim | Vidalı montaj, hareketli disk yok, konektör gerilim azaltıcı |
| 4 | Voltaj sıçraması / takviye akü | 9–36 V araç DC-DC, sigorta, ters polarite ve ani yükselme koruması |
| 5 | Oynatıcı takılması | Donanım watchdog + `Restart=always` + kontrollü gece resetleme |
| 6 | İçerik eskimesi (otobüs günlerce internetsiz) | İzleme panelinde "son senkron" alarmı; 72 saat geçen otobüs için tekniker uyarısı; USB yedek yolu |
| 7 | 4G kotası aşımı | Cihazda sert kota tavanı; büyük dosyalar WiFi'yi bekler; "acil" işaretiyle aşılabilir |
| 8 | Yanlış giriş / kapalı TV / kısık ses | HDMI-CEC ile aç, girişi kilitle, TV'yi kiosk moduna al, ses seviyesi kilidi |
| 9 | Şoför cihazı çekmesi | Kilitli kutu, erişilebilir port/düğme yok |
| 10 | "Benim reklamım dönmüyor" iddiası | Oynatma kanıtı + ekran görüntüsü raporu |
| 11 | İçeriğe müdahale (birinin başka video sokması) | İmzalı manifest, HTTPS, cihaz başına token, imza doğrulanmazsa oynatma yok |
| 12 | Yayından acil kaldırma gereği (hukuk/iptal) | MQTT kontrol kanalı: 200 baytlık komutla anında kaldırma |
| 13 | Alan WiFi'sinde 20 cihazın aynı anda boğması | 5 GHz, 2–3 AP, kademeli başlama (rastgele gecikme), yerel önbellek sunucusu |
| 14 | Tek noktaya bağımlılık (alan sunucusu ölürse) | 4G ana yol olduğu için alan sunucusu sadece hızlandırıcı; tek başına kritik değil |

---

## 10. Yapılamayacaklar / yapılmaması gerekenler (dürüst liste)

1. **"Her otobüs tam saat 09:00'da yeni reklama geçer" garantisi verilemez.** Dağıtım
   *eninde sonunda tutarlı* olur. Çözüm: dosyayı 24–48 saat önce it, `valid_from` ile
   hepsini aynı anda yayına al. Bunu satış sözleşmesine de yansıt.
2. **Canlı/gerçek zamanlı yayın yapma.** Kapsama boşluğunda ekran kararır. Her şey
   indir-sonra-oynat. Canlı sadece küçük şeyler için (alt yazı şeridi, acil duyuru).
3. **50 MB'lık dosyayı olduğu gibi dağıtmaya çalışma.** Sunucuda sıkıştır. Bu maddeyi
   atlarsan diğer bütün çözümler zorlaşır.
4. **Tek AP'ye 20 otobüs bindirme.** 3 dakikada sığmaz — yukarıdaki hesap bunu gösteriyor.
5. **Alan WiFi'sini tek bağlantı yolu yapma.** Otobüs o gün alana gelmezse elin kolun bağlanır
   ve acil kaldırma komutu iletemezsin.
6. **Tüketici microSD + 220V adaptör + ucuz TV box üçlüsüyle filoya çıkma.** Pilotta çalışır,
   50 araçta haftalık arıza üretir.
7. **Şoföre iş yükleme.** "Şoför şu düğmeye bassın" diye kurulan hiçbir süreç yürümez.
8. **İlk günden kendi CMS'ini yazmaya başlama.** Hazır çözümle yayına gir, sonra yaz.
9. **Elektrik hattına izinsiz dokunma.** Otobüs işletmecisinin (belediye/özel halk otobüsü)
   teknik onayı, montaj ve sigorta kuralları var. Bunu baştan yazılı al.
10. **LoRa/Sigfox ile video, uydu ile araç başı bağlantı düşünme.** Biri yetmez, diğeri absürt maliyetli.
11. **Yarım inmiş dosyayı oynatma, eski dosyayı yenisi doğrulanmadan silme.** İkisi de ekranı karartır.

---

## 11. Maliyet kalemleri

> Donanım/yazılım fiyatları araştırmadaki kaynaklardan; **Türkiye SIM/hat fiyatları
> doğrulanamadı**, operatörden kurumsal teklif alınmalı.

### Tek seferlik (otobüs başına)

| Kalem | Tutar |
|---|---|
| Araç sınıfı oynatıcı (dahili 4G+GPS) **veya** Pi + araç DC-DC + endüstriyel depolama | Orta seviye — tedarikçi teklifi alınacak |
| Ayrı endüstriyel router tercih edilirse (Teltonika RUT200 sınıfı) | ~$102'den başlıyor |
| Kablo, sigorta, kutu, montaj işçiliği | Düşük |
| Ekran değişimi gerekirse (HDMI yoksa) | Yüksek — önce mevcut ekranı ölç |

### Tek seferlik (merkez / alan)

| Kalem | Tutar |
|---|---|
| Alan WiFi: 2–3 AP (5 GHz) + switch + kablolama | Düşük–orta |
| Yerel önbellek sunucusu (mini PC + SSD) | Düşük |
| Alan internet: 4G CPE router (D) | Düşük |
| PtP köprü gerekirse (C): LiteBeam AC Gen2 × 2 | ~$130–200 + montaj |

### Aylık

| Kalem | Tutar |
|---|---|
| Otobüs başı M2M hat (500 MB–1 GB) | **Teklif alınacak** (aşım referansı: Vodafone 1 GB paketlerde 0,07 TL/MB) |
| Alan internet hattı (tek hat, tüm filo) | Düşük |
| CMS: Xibo self-hosted | **0** (sunucu maliyeti hariç) |
| CMS: Yodeck alternatifi | $8–16 / ekran / ay |
| CMS: PiSignage alternatifi | $8–12 / ekran / ay |
| Sunucu + depolama + trafik (kendi sisteminde) | Düşük (12,5 GB/ay için önemsiz) |

**Maliyet notu:** 50 ekran için Yodeck $8 × 50 = aylık $400. Xibo self-hosted aynı işi
bir VPS maliyetine yapar. Ekran sayısı 10'u geçtiği anda self-hosted açık ara kazanır.

---

## 12. Uygulama yol haritası

### Faz 0 — Ölçüm (1 hafta, neredeyse bedava)

- [ ] Mevcut TV'nin **markası, modeli, girişleri** (HDMI var mı?), besleme voltajı yazılsın.
- [ ] Otobüsün güç hattı ölçülsün: nominal voltaj, marş anı düşüşü, uygun sigorta noktası.
- [ ] Toplanma alanında **4G hız testi** (farklı saatlerde, otobüs içinde ve dışında).
- [ ] Otobüslerin alanda gerçekten **kaç dakika** durduğu 1 hafta kayda alınsın.
- [ ] Otobüs içinde araç halindeyken 4G hızı ölçülsün (güzergâh boyunca, ortalama ve boşluklar).
- [ ] İşletmeciden montaj ve elektrik bağlantısı için **yazılı onay** alınsın.
- [ ] Mevcut reklam videolarının gerçek süre/boyut/bitrate dökümü çıkarılsın.

### Faz 1 — Pilot (2–3 hafta, 2 otobüs)

- [ ] ffmpeg transcode hattı: gelen her video tek standarda çevrilsin (~2,5 Mbps).
- [ ] Xibo self-hosted kurulsun (tek VPS).
- [ ] 2 otobüse araç sınıfı oynatıcı + M2M SIM takılsın.
- [ ] Manuel USB yolu yedek olarak açık tutulsun.
- [ ] **Başarı ölçütü:** 2 hafta boyunca hiçbir otobüse USB taşımadan 3 reklam değişikliği yapıldı.
- [ ] Gerçek veriler ölçülsün: indirme süreleri, kesinti sayısı, tüketilen veri, çökme sayısı.

### Faz 2 — Yaygınlaştırma (4–6 hafta, 10–20 otobüs)

- [ ] Alana AP + yerel önbellek sunucusu + CPE router kurulsun.
- [ ] Kademeli indirme, kota tavanı, WiFi/4G politikası devreye alınsın.
- [ ] İzleme paneli: her otobüsün son senkron zamanı, sürümü, disk, sinyal, hata.
- [ ] 72 saat güncellenmeyen otobüs için otomatik uyarı.
- [ ] MQTT kontrol kanalı + acil kaldırma komutu.
- [ ] Salt-okunur kök + overlayfs + watchdog standart hale gelsin.

### Faz 3 — Ticarileştirme

- [ ] Oynatma kanıtı raporu (CSV/PDF), reklamveren başına.
- [ ] GPS'li konum raporu, hat/güzergâh bazlı hedefleme.
- [ ] Reklamveren self-servis yükleme + onay akışı.
- [ ] Faturalama entegrasyonu.
- [ ] (Opsiyonel, 50+ araçta) otobüsler arası P2P dağıtım.

---

## 13. Tek paragraf özet

Sorun 3 dakikalık pencere değil, dosya boyutu. Videoyu sunucuda 2,5 Mbps'e sıkıştır
(50 MB → ~10 MB), otobüse dahili 4G'li araç sınıfı oynatıcı tak, dosyayı HTTP Range ile
parça parça indir (kesilirse kaldığı yerden devam eder, tam inip hash doğrulanmadan
oynatılmaz), toplanma alanına da yerel önbellek sunucusu + WiFi koy ki bedava bant
genişliğini kullanasın; kontrol için ayrı ve çok küçük bir MQTT kanalı tut ki bir reklamı
saniyeler içinde kaldırabilesin. Pilotu 2 otobüste hazır CMS (Xibo self-hosted) ile
yap, kendi panelini gerçek veriyi gördükten sonra yaz. USB'yi ilk gün çöpe atma — yedek
yol olarak kalsın.

---

## Kaynaklar

- Xibo kesintili indirmeyi sürdürme: https://xibosignage.com/blog/introducing-a-threaded-more-robust-windows-client-with-improved-library-management
- Xibo / Yodeck karşılaştırma ve fiyat: https://digitalsignage.com/digital_signage/docs/guides/xibo-vs-yodeck-2026/
- Yodeck fiyatlandırma: https://checkthat.ai/brands/yodeck/pricing
- PiSignage çevrimdışı tabela: https://blog.pisignage.com/offline-digital-signage-how-to-keep-your-screens-running-when-the-internet-doesnt-guide/
- Çevrimdışı öncelikli tabela tasarımı: https://anorakstech.com/blog-offline-first.html
- HTTP Range ile devam eden indirme: https://dev.to/mandy8055/how-resumable-uploads-and-downloads-actually-work-4b9i
- aria2c kılavuzu (`-c`, parça doğrulama): https://aria2.github.io/manual/en/html/aria2c.html
- Dijital tabela video bitrate önerileri: https://community.appspace.com/tips-tricks-32/ensuring-optimal-video-bitrate-for-digital-signage-1625
- Raspberry Pi USB mass-storage gadget: https://magazine.raspberrypi.com/articles/pi-zero-w-smart-usb-flash-drive
- SD kart bozulmasını önleme: https://www.dprg.org/preventing-sd-card-corruption-on-a-raspberry-pi/
- Teltonika RUT200 (endüstriyel 4G router): https://www.teltonika-networks.com/products/routers/rut200
- Ubiquiti LiteBeam AC Gen2 (PtP köprü): https://www.getic.com/product/litebeam-ac-gen2
- Filo WiFi ile veri boşaltma: https://www.wefi.com/fleet-management-offload
- Toplu taşımada kablosuz veri boşaltma: https://www.linkedin.com/pulse/rapid-wireless-offloading-big-data-video-mass-transit-umberto-malesci
- Oynatma kanıtı (proof-of-play) standardı: https://easysignage.com/help/proof-of-play/proof-of-play/
- Oynatma kanıtı ve denetim: https://www.avnetwork.com/avnetwork/new-grandmas-and-1gb-network-at-miami-concert-hall
- Araç içi reklam ekranı tedarikçisi (TR): https://www.erpateknoloji.com/kategori/digital-signage-ekranlar
- Otobüs/minibüs dijital reklam ekranı (TR): http://dios.com.tr/urun/40/otobus-ve-minibusler-icin-dijital-reklam-ekrani
- Vodafone M2M paketleri: https://www.vodafone.com.tr/vodafone-business/cozumler/m2m-data-paketleri
- Netgsm M2M/IoT fiyatları: https://www.netgsm.com.tr/fiyatlar/m2m
- Turkcell M2M standart tarife: https://www.turkcell.com.tr/kurumsal/dijital-is-servisleri/iot-nesnelerin-interneti/m2m-standart-tarife-paketleri-kampanyasi
- M2M hat fiyatları genel bakış (TR): https://dijitaltakip.com.tr/blog/28/m2m-hat-fiyatlari-ve-guncel-tarifeler
