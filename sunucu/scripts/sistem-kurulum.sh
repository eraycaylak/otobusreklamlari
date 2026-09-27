#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# MERKEZ SUNUCU SISTEM KURULUMU (Debian/Ubuntu, systemd)
#
# NEDEN VAR: reklam-sunucu.service dosyasi /etc/reklam/sunucu.env ve
# /var/lib/reklam yollarina dayaniyor, ayrica `reklam` kullanicisini bekliyor.
# Hicbir betik bunlari olusturmuyordu; unit'i oldugu gibi kurmak sessiz bir
# basarisizlik uretiyordu:
#   - EnvironmentFile YOKSA systemd servisi BASLATMAZ
#   - Restart=always yuzunden 5 saniyede bir yeniden dener
#   - journalctl'e bakmayan biri icin bu "sunucu calismiyor, sebep yok" demektir
#
#   sudo ./scripts/sistem-kurulum.sh
# ---------------------------------------------------------------------------
set -euo pipefail

if [[ $EUID -ne 0 ]]; then echo "root olarak calistirin (sudo)"; exit 1; fi

KOK="$(cd "$(dirname "$0")/.." && pwd)"
KURULUM_DIZINI="${KURULUM_DIZINI:-/opt/reklam/sunucu}"
VERI_DIZINI="${VERI_DIZINI:-/var/lib/reklam}"
ENV_DIZINI="/etc/reklam"
ENV_DOSYA="$ENV_DIZINI/sunucu.env"

command -v node >/dev/null || { echo "node bulunamadi. Node.js 20+ kurun."; exit 1; }

echo "== Merkez sunucu kurulumu =="

# 1) Servis kullanicisi (giris yapamayan, ev dizini olmayan)
if ! id -u reklam >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin reklam
  echo "  kullanici olusturuldu: reklam"
fi

# 2) Dizinler
mkdir -p "$VERI_DIZINI" "$ENV_DIZINI" "$KURULUM_DIZINI"
chown -R reklam:reklam "$VERI_DIZINI"
chmod 700 "$VERI_DIZINI"      # icinde OZEL IMZALAMA ANAHTARI olacak
chmod 750 "$ENV_DIZINI"

# 3) Ortam dosyasi - ADMIN_TOKEN uretiliyor
if [[ -f "$ENV_DOSYA" ]]; then
  echo "  $ENV_DOSYA zaten var, DOKUNULMUYOR"
else
  TOKEN=$(node -e 'console.log(require("crypto").randomBytes(32).toString("base64url"))')
  cat > "$ENV_DOSYA" <<ENVSON
# Merkez sunucu ortam degiskenleri. Bu dosya SIR ICERIR.
ADMIN_TOKEN=$TOKEN
# Onumuzde kac GUVENILIR vekil var? Dogrudan erisimde 0.
# Onbellek kutusu/ters vekil arkasindaysa o hop sayisini yazin - 'true' DEGIL:
# istemcinin yazdigi X-Forwarded-For deneme sinirini atlatilabilir kilar.
TRUST_PROXY_HOPS=0
# Daypart bu saat diliminde yorumlanir ve manifestle cihaza gider.
TIMEZONE=Europe/Istanbul
# /content icin cihaz tokeni istensin mi (1 = evet). /app her zaman ister.
CONTENT_AUTH=1
ENVSON
  chown root:reklam "$ENV_DOSYA"
  chmod 640 "$ENV_DOSYA"
  echo ""
  echo "  ADMIN TOKEN URETILDI - panele girmek icin bu gerekli:"
  echo "    $TOKEN"
  echo "  (ayrica $ENV_DOSYA icinde duruyor)"
  echo ""
fi

# 4) Uygulama dosyalari
if [[ "$KOK" != "$KURULUM_DIZINI" ]]; then
  echo "  dosyalar kopyalaniyor: $KOK -> $KURULUM_DIZINI"
  mkdir -p "$KURULUM_DIZINI"
  tar -C "$KOK" --exclude=./data --exclude=./yedekler --exclude=./node_modules -cf - . \
    | tar -C "$KURULUM_DIZINI" -xf -
fi
( cd "$KURULUM_DIZINI" && npm install --omit=dev )
chown -R reklam:reklam "$KURULUM_DIZINI"

# 5) Imzalama anahtari (yoksa uret) - DATA_DIR unit'teki degerle AYNI olmali
if [[ ! -f "$VERI_DIZINI/keys/ed25519-private.pem" ]]; then
  echo "  imzalama anahtari uretiliyor..."
  ( cd "$KURULUM_DIZINI" && DATA_DIR="$VERI_DIZINI" sudo -u reklam node scripts/anahtar-uret.js )
else
  echo "  imzalama anahtari zaten var, DOKUNULMUYOR"
fi

# 6) Unit
install -m 644 "$KOK/reklam-sunucu.service" /etc/systemd/system/reklam-sunucu.service
systemctl daemon-reload
systemctl enable reklam-sunucu
systemctl restart reklam-sunucu
sleep 2
systemctl --no-pager --lines=10 status reklam-sunucu || true

echo ""
echo "Kurulum tamam. Yapilacaklar:"
echo "  1) ACIK anahtari alin ve APK'ya gomun (MANIFEST_PUBLIC_KEY):"
echo "       cd $KURULUM_DIZINI && DATA_DIR=$VERI_DIZINI node scripts/anahtar-goster.js"
echo "  2) Panel: http://<sunucu>:8080  (yukaridaki ADMIN TOKEN ile)"
echo "  3) YEDEK: $VERI_DIZINI/keys/ed25519-private.pem kaybolursa yeni manifest"
echo "     imzalanamaz ve TUM otobuslere elle gitmek gerekir. yedekle.sh'i cron'a alin:"
echo "       0 2 * * *  $KURULUM_DIZINI/scripts/yedekle.sh >> /var/log/reklam-yedek.log 2>&1"
echo "     Yedegi BASKA bir makineye kopyalayin."
