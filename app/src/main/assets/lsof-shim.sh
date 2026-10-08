#!/bin/bash
# lsof palsu untuk Android + proot.
# agy-server mencari port acak language_server lewat `lsof -sTCP:LISTEN` lalu /proc/net/tcp,
# tetapi Android (10+) melarang aplikasi membaca /proc/net. language_server mencatat port-nya
# di language-server.log, jadi keluaran lsof disusun dari log tersebut (format lsof baku).
LOG=/root/.agy-remote/language-server.log
want=""
fmt=""
prev=""
for a in "$@"; do
  case "$a" in
    -F*) fmt=1 ;;
    -p[0-9]*) want="${a#-p}" ;;
  esac
  if [ "$prev" = "-p" ]; then want="$a"; fi
  prev="$a"
done

[ -r "$LOG" ] || exit 1
n=$(grep -an 'Starting language server process with pid' "$LOG" | tail -n 1 | cut -d: -f1)
[ -n "$n" ] || exit 1
lspid=$(sed -n "${n}p" "$LOG" | sed -E 's/.*with pid ([0-9]+).*/\1/')
case "$lspid" in ''|*[!0-9]*) exit 1 ;; esac
[ -d "/proc/$lspid" ] || exit 1

# -p boleh berisi daftar "1,2,3"
if [ -n "$want" ]; then
  case ",$want," in
    *",$lspid,"*) ;;
    *) exit 1 ;;
  esac
fi

ports=$(tail -n +"$n" "$LOG" | grep -a 'listening on random port at' | sed -E 's/.*port at ([0-9]+).*/\1/')
[ -n "$ports" ] || exit 1

if [ -n "$fmt" ]; then
  echo "p$lspid"
  for p in $ports; do
    echo "f10"
    echo "n127.0.0.1:$p"
  done
else
  echo "COMMAND    PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME"
  for p in $ports; do
    echo "language_ $lspid root   10u  IPv4 $((100000 + p))      0t0  TCP 127.0.0.1:$p (LISTEN)"
  done
fi
exit 0
