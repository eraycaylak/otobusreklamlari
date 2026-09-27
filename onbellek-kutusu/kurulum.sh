#!/usr/bin/env bash
# Onbellek kutusu ilk kurulum (Debian/Ubuntu veya Raspberry Pi OS)
set -euo pipefail

echo "== Otobus reklam - nokta onbellek kutusu kurulumu =="

if [[ $EUID -ne 0 ]]; then echo "root olarak calistirin (sudo)"; exit 1; fi

KAYNAK="$(cd "$(dirname "$0")" && pwd)"

apt-get update
# flock (util-linux) ve coreutils zaten kurulu olur ama ayna.sh ikisine de
# guveniyor: flock es zamanli aynalamayi, sha256sum butunluk denetimini saglar.
apt-get install -y nginx rsync openssh-client util-linux coreutils

mkdir -p /srv/reklam/content /srv/reklam/app
mkdir -p /var/cache/nginx/reklam /opt/reklam
chown -R www-data:www-data /srv/reklam /var/cache/nginx/reklam

install -m 644 "$KAYNAK/nginx.conf" /etc/nginx/sites-available/reklam
ln -sf /etc/nginx/sites-available/reklam /etc/nginx/sites-enabled/reklam
rm -f /etc/nginx/sites-enabled/default

# ---------------------------------------------------------------------------
# ERISIM LISTESINI BU KUTUNUN GERCEK ALT AGINA AYARLA.
#
# nginx.conf icinde /content ve /app yalnizca noktanin alt agina cevap veriyor
# (dosyalar yerel aynadan servis edildigi icin merkezdeki cihaz tokeni kontrolu
# buraya uygulanamaz - dogrulamak icin merkeze sormak gerekirdi, ki tam da PtMP
# kopukken calismaz).
#
# Sablondaki deger bir YER TUTUCU. Duzeltilmezse otobusler 403 alir ve hicbir
# icerik inmez - ustelik sebep hicbir yerde gorunmez. Bu yuzden kutunun kendi
# alt agini tespit edip yaziyoruz; operator sonra elle degistirebilir.
# ---------------------------------------------------------------------------
ALTAG=$(ip -o -4 route show scope link 2>/dev/null | awk '$1 ~ /\// && $1 !~ /^169\.254/ {print $1; exit}')
if [[ -n "${ALTAG:-}" ]]; then
  sed -i "s|allow 10\.20\.0\.0/24;|allow $ALTAG;|g" /etc/nginx/sites-available/reklam
  echo "  Erisim listesi bu kutunun alt agina ayarlandi: $ALTAG"
else
  echo "  DIKKAT: alt ag tespit edilemedi. /etc/nginx/sites-available/reklam icindeki"
  echo "          'allow 10.20.0.0/24;' satirlarini KENDI aginiza gore duzeltin,"
  echo "          yoksa otobusler 403 alir ve hicbir icerik inmez."
fi

install -m 755 "$KAYNAK/ayna.sh" /opt/reklam/ayna.sh

cat > /etc/cron.d/reklam-ayna <<'CRON'
# Icerigi merkezden saatte bir aynala. Otobusler geldiginde her sey yerelde hazir olsun.
# Dakika 7: tam saatte toplanan diger is yukunden kacinmak icin.
7 * * * * root /opt/reklam/ayna.sh >> /var/log/reklam-ayna.log 2>&1
CRON

# Log donusumu: kucuk bir kutuda bu dosya yillar icinde diski doldurabilir -
# yani teshis icin tuttugumuz kayit, teshis edilecek arizanin sebebi olur.
cat > /etc/logrotate.d/reklam-ayna <<'ROT'
/var/log/reklam-ayna.log {
    weekly
    rotate 8
    compress
    missingok
    notifempty
    copytruncate
}
ROT

nginx -t
systemctl reload nginx

echo ""
echo "Kurulum tamam. Yapilacaklar:"
echo "  1) /etc/nginx/sites-available/reklam icindeki 'upstream merkez' adresini duzeltin"
echo "  2) ROOT kullanicisi icin merkeze parolasiz SSH anahtari kurun (ayna.sh cron'da"
echo "     root olarak calisiyor):"
echo "       sudo ssh-keygen -t ed25519 -N '' -f /root/.ssh/id_ed25519"
echo "       sudo ssh-copy-id -i /root/.ssh/id_ed25519 reklam@<MERKEZ-IP>"
echo "  3) Ilk aynalamayi elle calistirin:  /opt/reklam/ayna.sh"
echo "  4) Dogrulayin:  curl -sI -r 0-1023 http://localhost/content/<sha>.mp4 | head -3"
echo "     -> '206 Partial Content' gormeniz SART. Gormuyorsaniz sistem calismaz."
echo "  5) Bir OTOBUSUN agindan da dogrulayin (erisim listesi dogru mu):"
echo "     -> 403 aliyorsaniz 'allow' satirlari o otobusun alt agini kapsamiyor."
