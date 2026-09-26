#!/usr/bin/env bash
# Onbellek kutusu ilk kurulum (Debian/Ubuntu veya Raspberry Pi OS)
set -euo pipefail

echo "== Otobus reklam - nokta onbellek kutusu kurulumu =="

if [[ $EUID -ne 0 ]]; then echo "root olarak calistirin (sudo)"; exit 1; fi

apt-get update
apt-get install -y nginx rsync openssh-client

mkdir -p /srv/reklam/content /srv/reklam/app
mkdir -p /var/cache/nginx/reklam
chown -R www-data:www-data /srv/reklam /var/cache/nginx/reklam

install -m 644 "$(dirname "$0")/nginx.conf" /etc/nginx/sites-available/reklam
ln -sf /etc/nginx/sites-available/reklam /etc/nginx/sites-enabled/reklam
rm -f /etc/nginx/sites-enabled/default

install -m 755 "$(dirname "$0")/ayna.sh" /opt/reklam/ayna.sh 2>/dev/null || {
  mkdir -p /opt/reklam && install -m 755 "$(dirname "$0")/ayna.sh" /opt/reklam/ayna.sh
}

cat > /etc/cron.d/reklam-ayna <<'CRON'
# Icerigi merkezden saatte bir aynala. Otobusler geldiginde her sey yerelde hazir olsun.
7 * * * * root /opt/reklam/ayna.sh >> /var/log/reklam-ayna.log 2>&1
CRON

nginx -t
systemctl reload nginx

echo ""
echo "Kurulum tamam. Yapilacaklar:"
echo "  1) /etc/nginx/sites-available/reklam icindeki 'upstream merkez' adresini duzeltin"
echo "  2) Merkeze parolasiz SSH anahtari kurun (rsync icin):  ssh-keygen && ssh-copy-id ..."
echo "  3) Ilk aynalamayi elle calistirin:  /opt/reklam/ayna.sh"
echo "  4) Dogrulayin:  curl -sI -r 0-1023 http://localhost/content/<sha>.mp4 | head -3"
echo "     -> '206 Partial Content' gormeniz SART. Gormuyorsaniz sistem calismaz."
