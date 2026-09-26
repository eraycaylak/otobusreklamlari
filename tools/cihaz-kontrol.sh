#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# STICK UYGUNLUK KONTROLU
#
# 50 cihaz satin almadan ONCE bir tanede calistirin.
# Buradaki "KALDI" satirlarindan biri bile projeyi durdurabilir - ozellikle
# "hesap ekli" ve "zaten bir cihaz sahibi var" satirlari.
#
# Kullanim:  ./tools/cihaz-kontrol.sh
# ---------------------------------------------------------------------------
set -uo pipefail

GECTI=0; KALDI=0; UYARI=0
ye() { printf '  \033[32m[GECTI]\033[0m %s\n' "$1"; GECTI=$((GECTI+1)); }
ha() { printf '  \033[31m[KALDI]\033[0m %s\n' "$1"; KALDI=$((KALDI+1)); }
uy() { printf '  \033[33m[DIKKAT]\033[0m %s\n' "$1"; UYARI=$((UYARI+1)); }
bilgi() { printf '  \033[90m%s\033[0m\n' "$1"; }
baslik() { printf '\n\033[1m%s\033[0m\n' "$1"; }

sh_() { adb shell "$@" 2>/dev/null | tr -d '\r'; }

command -v adb >/dev/null || { echo "adb bulunamadi. Android platform-tools kurun."; exit 1; }

baslik "0. Baglanti"
CIHAZ=$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -1)
if [[ -z "$CIHAZ" ]]; then
  ha "adb ile cihaz gorulmuyor"
  bilgi "Stick'te Ayarlar > Cihaz tercihleri > Hakkinda > Derleme numarasina 7 kez basin,"
  bilgi "sonra Gelistirici secenekleri > USB/Kablosuz hata ayiklama'yi acin."
  bilgi "Kablosuz icin:  adb connect <stick-ip>:5555"
  exit 1
fi
ye "cihaz baglI: $CIHAZ"
bilgi "$(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model) / $(sh_ getprop ro.product.device)"

baslik "1. Android surumu"
SDK=$(sh_ getprop ro.build.version.sdk)
SURUM=$(sh_ getprop ro.build.version.release)
bilgi "Android $SURUM (API $SDK)"
if [[ "${SDK:-0}" -ge 24 ]]; then ye "API $SDK >= 24 (minimum karsilaniyor)"
else ha "API $SDK cok eski - uygulama minSdk 24 istiyor"; fi
if [[ "${SDK:-0}" -ge 29 ]]; then
  uy "API 29+: WiFi profilini SADECE cihaz sahibi yazabilir (addNetwork aksi halde -1 doner)"
fi

baslik "2. Bellek ve depolama"
RAM_KB=$(sh_ cat /proc/meminfo | awk '/MemTotal/{print $2}')
RAM_MB=$(( ${RAM_KB:-0} / 1024 ))
bilgi "RAM: ${RAM_MB} MB"
if   [[ $RAM_MB -ge 1800 ]]; then ye "RAM yeterli"
elif [[ $RAM_MB -ge 900 ]];  then uy "RAM dusuk (${RAM_MB} MB) - 1080p oynatmada takilma riski"
else ha "RAM cok dusuk (${RAM_MB} MB)"; fi

BOS=$(sh_ df /data | awk 'NR==2{print $4}')
BOS_MB=$(( ${BOS:-0} / 1024 ))
bilgi "/data bos alan: ${BOS_MB} MB"
if   [[ $BOS_MB -ge 4000 ]]; then ye "depolama yeterli"
elif [[ $BOS_MB -ge 1500 ]]; then uy "depolama sinirli - kutuphaneyi kucuk tutun"
else ha "depolama yetersiz (${BOS_MB} MB)"; fi

baslik "3. Ekran"
bilgi "cozunurluk: $(sh_ wm size | sed 's/.*: //')  yogunluk: $(sh_ wm density | sed 's/.*: //')"

baslik "4. WiFi 5 GHz destegi"
TARAMA=$(sh_ cmd wifi list-scan-results)
if echo "$TARAMA" | grep -qE '5[0-9]{3}'; then
  ye "5 GHz tarama sonucu gorundu"
elif sh_ dumpsys wifi | grep -qiE '5GHz|Band 2|11ac|11ax'; then
  ye "5 GHz destegi isaretleri var"
else
  uy "5 GHz DOGRULANAMADI - 2.4 GHz tek bant olabilir"
  bilgi "2.4 GHz tek bantsa noktaya 2-3 AP koyun (kanal 1/6/11); tek AP yetmez."
  bilgi "Elle dogrulama: stick'i 5 GHz yayin yapan bir AP'ye baglamayi deneyin."
fi

baslik "5. H.264 donanim cozucu"
KODEK=$(sh_ cat /etc/media_codecs.xml)
if echo "$KODEK" | grep -qiE 'video/avc'; then
  if echo "$KODEK" | grep -iE 'video/avc' -B3 | grep -qiE 'OMX\.|c2\.'; then
    ye "H.264 (AVC) donanim cozucu var"
  else
    uy "H.264 var ama donanim cozucu dogrulanamadi"
  fi
else
  uy "media_codecs.xml okunamadi - pilotta gercek video ile test edin"
fi
if echo "$KODEK" | grep -qiE 'video/hevc'; then
  bilgi "HEVC de var; yine de H.264 kullanin - butun filoda ayni davranis garanti olsun"
else
  bilgi "HEVC yok -> transcode standardinin H.264 olmasi DOGRU karar"
fi

baslik "6. CIHAZ SAHIBI (device owner) yapilabilir mi?  << EN KRITIK >>"
HESAP=$(sh_ dumpsys account | grep -c 'Account {')
SAHIPLER=$(sh_ dumpsys device_policy | grep -iA2 'Device Owner')
if [[ "${HESAP:-0}" -gt 0 ]]; then
  ha "cihazda $HESAP hesap ekli -> dpm set-device-owner BASARISIZ OLACAK"
  bilgi "Cozum: Ayarlar > Sistem > Sifirlama ile FABRIKA AYARLARINA donun,"
  bilgi "kurulumda HICBIR Google hesabi eklemeyin, sonra provizyonu yapin."
else
  ye "ekli hesap yok - device owner kurulabilir"
fi
if echo "$SAHIPLER" | grep -qi 'ComponentInfo'; then
  ha "cihazda ZATEN bir device owner var: $(echo "$SAHIPLER" | head -3 | tr '\n' ' ')"
  bilgi "Fabrika ayarlarina donmeden ikinci bir sahip atanamaz."
else
  ye "atanmis device owner yok"
fi
MARKA=$(sh_ getprop ro.product.manufacturer | tr '[:upper:]' '[:lower:]')
if echo "$MARKA" | grep -qE 'amazon'; then
  ha "Fire OS tespit edildi - device owner pratikte kapalidir, bu cihazi KULLANMAYIN"
fi

baslik "7. HDMI-CEC"
if sh_ pm list features | grep -qi 'hdmi'; then
  ye "CEC ile ilgili sistem ozelligi bulundu"
else
  uy "CEC dogrulanamadi"
fi
bilgi "NOT: Normal bir uygulama CEC komutu GONDEREMEZ (HdmiControlManager sistem API'sidir)."
bilgi "TV'yi acmak icin CEC'i cihaz AYARLARINDAN acin ve TV'yi kontak hattina baglayin."

baslik "SONUC"
printf '  gecti: %d   dikkat: %d   kaldi: %d\n' "$GECTI" "$UYARI" "$KALDI"
if [[ $KALDI -gt 0 ]]; then
  printf '\n\033[31m  Bu cihaz oldugu haliyle KULLANILAMAZ. Yukaridaki [KALDI] satirlarini cozun.\033[0m\n'
  exit 1
fi
printf '\n\033[32m  Cihaz uygun. Sirada: tools/provizyon.sh\033[0m\n'
