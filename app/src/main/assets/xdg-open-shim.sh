#!/bin/bash
# xdg-open / x-www-browser palsu untuk Android + proot.
# language_server membuka peramban untuk login Google; di guest tidak ada peramban, sehingga UI
# macet di "Awaiting Authentication". URL dititipkan ke aplikasi (dibaca AgyService) yang
# membukanya di peramban Android — tempat pemilih akun Google berjalan normal.
url=""
for a in "$@"; do
  case "$a" in
    http://*|https://*) url="$a" ;;
  esac
done
[ -n "$url" ] || exit 1
dir=/root/.agy/openurl
mkdir -p "$dir"
f="$dir/$(date +%s%N).url"
printf '%s' "$url" > "$f.tmp" && mv "$f.tmp" "$f"
exit 0
