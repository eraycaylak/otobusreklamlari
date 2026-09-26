#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# CIHAZ PROVIZYONU
#
# Cihaz KUTUDAN YENI CIKMIS veya fabrika ayarlarina donmus olmali ve
# HICBIR hesap ekli OLMAMALI. Aksi halde device owner atanamaz ve
# sonradan duzeltmenin tek yolu yine fabrika ayarlarina donmektir.
#
# Kullanim:
#   ./tools/provizyon.sh OTOBUS-014 \
#       --sunucu http://10.20.0.10 \
#       --ssid "REKLAM-AP" --psk "parola" \
#       --api http://127.0.0.1:8080 --admin-token "..." \
#       --apk android/app/build/outputs/apk/release/app-release.apk \
#       --secret "gradle.properties icindeki PROVISION_SECRET"
# ---------------------------------------------------------------------------
set -euo pipefail

PAKET="com.otobusreklam.player"
ALICI="$PAKET/.admin.AdminReceiver"
PROV_ALICI="$PAKET/.provision.ProvisionReceiver"

CIHAZ_ID="${1:-}"; shift || true
[[ -z "$CIHAZ_ID" ]] && { echo "Kullanim: $0 <CIHAZ-ID> [secenekler]"; exit 1; }

SUNUCU=""; SSID=""; PSK=""; API="http://127.0.0.1:8080"; ADMIN_TOKEN="${ADMIN_TOKEN:-}"
APK=""; SECRET="${PROVISION_SECRET:-}"; GRUP="default"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sunucu) SUNUCU="$2"; shift 2;;
    --ssid) SSID="$2"; shift 2;;
    --psk) PSK="$2"; shift 2;;
    --api) API="$2"; shift 2;;
    --admin-token) ADMIN_TOKEN="$2"; shift 2;;
    --apk) APK="$2"; shift 2;;
    --secret) SECRET="$2"; shift 2;;
    --grup) GRUP="$2"; shift 2;;
    *) echo "bilinmeyen secenek: $1"; exit 1;;
  esac
done

[[ -z "$SUNUCU" ]] && { echo "--sunucu zorunlu (ornek: http://10.20.0.10)"; exit 1; }
[[ -z "$SECRET" ]] && { echo "--secret zorunlu (gradle.properties: PROVISION_SECRET)"; exit 1; }
[[ -z "$ADMIN_TOKEN" ]] && { echo "--admin-token zorunlu"; exit 1; }

adim() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
hata() { printf '\033[31mHATA: %s\033[0m\n' "$1"; exit 1; }

adim "1/6  Cihaz kontrolu"
adb get-state >/dev/null 2>&1 || hata "adb ile cihaz gorulmuyor"
HESAP=$(adb shell dumpsys account 2>/dev/null | grep -c 'Account {' | tr -d '\r')
[[ "${HESAP:-0}" -gt 0 ]] && hata "cihazda $HESAP hesap ekli - device owner atanamaz. Fabrika ayarlarina donun."

adim "2/6  Sunucuda cihaz kaydi"
KAYIT=$(curl -fsS -X POST "$API/api/admin/device" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$CIHAZ_ID\",\"group\":\"$GRUP\",\"label\":\"$CIHAZ_ID\"}") \
  || hata "sunucuya kayit basarisiz (API adresi ve admin token dogru mu?)"
TOKEN=$(echo "$KAYIT" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[[ -z "$TOKEN" ]] && hata "sunucudan token alinamadi"
echo "    token alindi (${#TOKEN} karakter)"

adim "3/6  Uygulama kurulumu"
if [[ -n "$APK" ]]; then
  [[ -f "$APK" ]] || hata "APK bulunamadi: $APK"
  adb install -r "$APK" || hata "kurulum basarisiz"
else
  adb shell pm list packages | grep -q "$PAKET" || hata "uygulama kurulu degil ve --apk verilmedi"
  echo "    uygulama zaten kurulu"
fi

adim "4/6  Device owner atama  << bu adim basarisiz olursa devam etmeyin >>"
if adb shell dumpsys device_policy 2>/dev/null | grep -qi "$PAKET"; then
  echo "    zaten cihaz sahibi"
else
  CIKTI=$(adb shell dpm set-device-owner "$ALICI" 2>&1 | tr -d '\r')
  echo "    $CIKTI"
  echo "$CIKTI" | grep -qi 'Success' || hata "device owner atanamadi. Fabrika ayarlarina donup hesap eklemeden tekrar deneyin."
fi

adim "5/6  Provizyon yayini"
SONUC=$(adb shell am broadcast -a com.otobusreklam.player.PROVISION -n "$PROV_ALICI" \
  --es secret "$SECRET" \
  --es deviceId "$CIHAZ_ID" \
  --es token "$TOKEN" \
  --es baseUrl "$SUNUCU" \
  --es apiUrl "$SUNUCU" \
  --es ssid "$SSID" \
  --es psk "$PSK" 2>&1 | tr -d '\r')
echo "    $SONUC"
echo "$SONUC" | grep -q 'TAMAM' || hata "provizyon reddedildi (sir yanlis veya cihaz zaten provizyonlu)"
echo "$SONUC" | grep -q 'sahip=evet' || echo "    UYARI: cihaz sahibi degil - sessiz guncelleme ve WiFi calismayacak"
echo "$SONUC" | grep -q 'wifi=yazildi' || echo "    UYARI: WiFi profili yazilamadi"

adim "6/6  Dogrulama"
adb shell am start -n "$PAKET/.player.PlayerActivity" >/dev/null 2>&1 || true
sleep 3
echo "    calisan paket: $(adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')"

cat <<SON

Provizyon tamamlandi: $CIHAZ_ID

Montajdan ONCE elle dogrulayin:
  [ ] Ekranda evergreen icerik donuyor (once sunucuya bir evergreen kampanya yukleyin)
  [ ] Cihaz noktadaki AP'ye KENDI baglaniyor (WiFi'yi kapatip acarak test edin)
  [ ] Panelde heartbeat gorunuyor
  [ ] Kumanda hicbir sey yapmiyor (kiosk aktif)
  [ ] Besleme AYRI 5V/2A kaynaktan - TV'nin USB'sinden DEGIL
  [ ] Teshis ekrani: kumandada INFO/MENU tusu -> "sahip: evet" yaziyor

SON
