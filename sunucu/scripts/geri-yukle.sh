#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# MERKEZ SUNUCU GERI YUKLEME
#
# NEDEN VAR: yedek almak ile KURTARILABILIR OLMAK ayni sey degildir. yedekle.sh
# vardi ve dogrulanmis bir tar.gz uretiyordu, ama geri yuklemenin nasil yapilacagi
# hicbir yerde YAZMIYORDU ve hic DENENMEMISTI. Felaket aninda ogrenilen bir yordam,
# olmayan bir yordamdir.
#
# Kritik olan sey: data/keys/ed25519-private.pem. O anahtar kaybolursa yeni manifest
# imzalanamaz ve YENI bir anahtarla devam etmek, sahadaki TUM cihazlarin yeniden
# provizyonu (her otobuse tek tek gitmek) demektir - cunku acik anahtar APK'ya
# DERLEME ZAMANINDA gomuluyor. Bu betik, yedekteki anahtarin APK'daki acik anahtarla
# ESLESIP ESLESMEDIGINI de kontrol eder.
#
# Kullanim:
#   ./scripts/geri-yukle.sh yedekler/reklam-yedek-20260927-020000.tar.gz
#   ./scripts/geri-yukle.sh <yedek> --kuru-prova        # hicbir sey yazma, sadece anlat
#   ./scripts/geri-yukle.sh <yedek> --acik-anahtar <base64>   # APK ile eslesme kontrolu
#
# Adimlari ONCE kuru prova ile okuyun. Betik, VAR OLAN veriyi asla sessizce EZMEZ:
# mevcut veri dizini yana alinir.
# ---------------------------------------------------------------------------
set -euo pipefail

YEDEK="${1:-}"
[[ -z "$YEDEK" ]] && { echo "Kullanim: $0 <yedek.tar.gz> [--kuru-prova] [--acik-anahtar <base64>]"; exit 1; }
shift

KURU=0
APK_ANAHTAR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --kuru-prova) KURU=1; shift;;
    --acik-anahtar) APK_ANAHTAR="${2:-}"; shift 2;;
    *) echo "bilinmeyen secenek: $1"; exit 1;;
  esac
done

KOK="$(cd "$(dirname "$0")/.." && pwd)"
VERI="${DATA_DIR:-$KOK/data}"

hata() { printf '\033[31mHATA: %s\033[0m\n' "$1" >&2; exit 1; }
adim() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

[[ -f "$YEDEK" ]] || hata "yedek dosyasi yok: $YEDEK"

adim "1/6  Yedek butunlugu"
tar -tzf "$YEDEK" >/dev/null 2>&1 || hata "yedek okunamiyor veya bozuk: $YEDEK"
ICERIK=$(tar -tzf "$YEDEK")
echo "    $(printf '%s\n' "$ICERIK" | wc -l) girdi"

adim "2/6  Zorunlu parcalar var mi?"
for gerekli in './keys/ed25519-private.pem' './keys/ed25519-public.pem' './db.json'; do
  printf '%s\n' "$ICERIK" | grep -qx "$gerekli" ||
    hata "yedekte $gerekli YOK. Bu yedekle kurtarma YAPILAMAZ (imzalama anahtari/veritabani eksik)."
done
echo "    anahtar cifti ve db.json yedekte var"

adim "3/6  Gecici cikarma ve dogrulama"
GECICI=$(mktemp -d)
# shellcheck disable=SC2064  # GECICI simdi genislemeli
trap "rm -rf '$GECICI'" EXIT
umask 077
tar -xzf "$YEDEK" -C "$GECICI"

# db.json gercekten ayristirilabilir mi? (Bozuk bir db.json ile "kurtarilmis"
# sayilmak, sunucunun bos bir veritabaniyla acilmasindan daha kotudur.)
command -v node >/dev/null 2>&1 || hata "node bulunamadi (db.json dogrulanamaz)"
node -e '
  const fs = require("fs")
  const s = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))
  const c = (o) => Object.keys(o || {}).length
  console.log(`    db.json gecerli: ${c(s.devices)} cihaz, ${c(s.campaigns)} kampanya, ${c(s.items)} icerik`)
  if (!c(s.devices)) { console.error("    UYARI: yedekte HIC CIHAZ yok - dogru yedek mi?") }
' "$GECICI/db.json" || hata "db.json AYRISTIRILAMADI - bu yedek kullanilamaz"

# Anahtar cifti kendi icinde tutarli mi? (Eslesmeyen cift, sunucunun acilmasini
# engeller - ama bunu KURTARMA sirasinda bilmek gerekir, sonra degil.)
node -e '
  const crypto = require("crypto"), fs = require("fs")
  const priv = crypto.createPrivateKey(fs.readFileSync(process.argv[1], "utf8"))
  const pub = crypto.createPublicKey(fs.readFileSync(process.argv[2], "utf8"))
  const d = Buffer.from("geri-yukleme-self-test")
  if (!crypto.verify(null, d, pub, crypto.sign(null, d, priv))) {
    console.error("    ANAHTAR CIFTI ESLESMIYOR")
    process.exit(1)
  }
  const der = pub.export({ format: "der", type: "spki" })
  console.log("    acik anahtar (APK icin): " + der.subarray(der.length - 32).toString("base64"))
' "$GECICI/keys/ed25519-private.pem" "$GECICI/keys/ed25519-public.pem" ||
  hata "yedekteki anahtar cifti eslesmiyor - bu yedekle imzalama yapilamaz"

adim "4/6  Sahadaki APK ile eslesme"
if [[ -n "$APK_ANAHTAR" ]]; then
  YEDEK_ANAHTAR=$(node -e '
    const crypto = require("crypto"), fs = require("fs")
    const pub = crypto.createPublicKey(fs.readFileSync(process.argv[1], "utf8"))
    const der = pub.export({ format: "der", type: "spki" })
    process.stdout.write(der.subarray(der.length - 32).toString("base64"))
  ' "$GECICI/keys/ed25519-public.pem")
  if [[ "$YEDEK_ANAHTAR" == "$APK_ANAHTAR" ]]; then
    echo "    ESLESIYOR - sahadaki cihazlar bu anahtarla imzalanan manifestleri KABUL EDER"
  else
    echo "    ESLESMIYOR!"
    echo "      yedekteki : $YEDEK_ANAHTAR"
    echo "      APK'daki  : $APK_ANAHTAR"
    hata "Bu yedekle devam etmek, sahadaki TUM cihazlarin manifest imzasini reddetmesi demektir. Dogru yedegi bulun."
  fi
else
  echo "    ATLANDI. Sahadaki APK'nin acik anahtarini vermediniz."
  echo "    ONEMLI: acik anahtar APK'ya DERLEME ZAMANINDA gomulur. Yedekteki anahtar"
  echo "    sahadaki APK'nin bekledigi anahtar DEGILSE hicbir cihaz senkron olamaz."
  echo "    Kontrol icin: android/gradle.properties -> MANIFEST_PUBLIC_KEY degerini"
  echo "    --acik-anahtar ile bu betige verin."
fi

adim "5/6  Yapilacaklar"
cat <<PLAN
    1) Servis durdurulacak            : systemctl stop reklam-sunucu
    2) Mevcut veri yana alinacak      : $VERI -> $VERI.eski-<damga>
    3) Yedek acilacak                 : $YEDEK -> $VERI
    4) Izinler                        : keys/ 700, ozel anahtar 600
    5) Servis baslatilacak            : systemctl start reklam-sunucu
    6) Dogrulama                      : /saglik + panelde cihaz sayisi

    NOT: 'content' (videolar) varsayilan yedekte YOKTUR. Eksik videolar panelden
    yeniden yuklenir; kampanya kayitlari yedekte oldugu icin ayni sha256 ile
    yuklendiklerinde kampanyalar KENDILIGINDEN calisir.
PLAN

if [[ $KURU -eq 1 ]]; then
  adim "6/6  KURU PROVA - hicbir sey yazilmadi"
  echo "    Gercekten geri yuklemek icin --kuru-prova olmadan calistirin."
  exit 0
fi

adim "6/6  Geri yukleme"
printf '    Devam edilsin mi? Mevcut %s yana alinacak. [evet/HAYIR] ' "$VERI"
read -r ONAY
[[ "$ONAY" == "evet" ]] || { echo "    iptal edildi"; exit 1; }

if command -v systemctl >/dev/null 2>&1; then
  systemctl stop reklam-sunucu 2>/dev/null || echo "    (servis durdurulamadi/yok - elle kontrol edin)"
fi

if [[ -d "$VERI" ]]; then
  ESKI="$VERI.eski-$(date +%Y%m%d-%H%M%S)"
  mv "$VERI" "$ESKI"
  echo "    mevcut veri yana alindi: $ESKI"
fi

mkdir -p "$VERI"
tar -xzf "$YEDEK" -C "$VERI"
chmod 700 "$VERI/keys"
chmod 600 "$VERI/keys/"*.pem
echo "    geri yuklendi: $VERI"

if command -v systemctl >/dev/null 2>&1; then
  systemctl start reklam-sunucu 2>/dev/null || echo "    (servis baslatilamadi - elle baslatin)"
  sleep 2
  curl -fsS http://127.0.0.1:8080/saglik >/dev/null 2>&1 &&
    echo "    /saglik yanit veriyor" ||
    echo "    UYARI: /saglik yanit vermiyor - journalctl -u reklam-sunucu -n 50"
fi

cat <<SON

Geri yukleme tamamlandi.

Elle dogrulayin:
  [ ] Panelde cihaz sayisi yedekteki sayiyla ayni
  [ ] Bir otobus senkron oldu (heartbeat geldi)
  [ ] Eksik videolar panelden yeniden yuklendi
  [ ] Yana alinan dizin ($VERI.eski-*) bir sure SAKLANSIN, sonra silin

SON
