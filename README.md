# Antigravity IDE (Android)

Debian (proot, tanpa root) + [Antigravity Server](https://github.com/AFSlayer/antigravity-server) + WebView.
Seluruh server berjalan **di dalam perangkat**; WebView membuka `http://127.0.0.1:8765`.
Arsitektur dasarnya sama dengan CodeX Studio (Debian + code-server), produknya diganti.

```
MainActivity ─ WebView ─► http://127.0.0.1:8765
     │ start()
     ▼
AgyService (foreground, auto-restart, notifikasi)
     │  1. LinuxRuntime.prepareForRun  (resolv.conf, CA bundle, /proc palsu, direktori)
     │  2. AgyServer.ensureProvisioned (agy-server config + passwd, sekali, via GuestExec)
     │  3. proot -r files/linux/debian … /bin/bash /root/.agy/start.sh
     ▼
agy-server serve  (proxy + login PBKDF2 + patch UI mobile, :8765)
     └─► language_server --standalone  (inti Antigravity resmi)  ─► Google Cloud Code API
```

## Lapisan kode (`app/src/main/java/com/antigravity/ide/`)

| Paket | Isi | Tahu soal Antigravity? |
|---|---|---|
| `core/` | `Net` (unduh resume+retry), `Archive` (tar/xz/gz/deb, `extractSingle`), `StateStore`, `Pins` | hanya URL di `Pins` |
| `runtime/` | `LinuxRuntime` (proot, env, rootfs, CA bundle), `GuestExec` (perintah sekali jalan di guest) | **tidak** |
| `agy/` | `AgyServer` (modul produk), `AgyService` (siklus hidup server) | **ya** — satu-satunya tempat |
| `install/` | `Installer` (proot → Debian → agy-server + language_server → finalisasi atomik) | memanggil `AgyServer` |
| `ui/` | `AgyWebView`, `KeyPad`, `MacKeyboard` (Ctrl/Cmd/Fn), `MenuScreen` | tidak |

Mengganti/menambah produk guest = ubah `agy/` + `Pins.kt` (+ langkah di `Installer`). Menambah perintah
guest (apt, git, dsb.) = panggil `GuestExec.run(ctx, listOf(...))`.

## Alur penting

- **Instalasi** (staging → rename atomik): proot (Termux) → rootfs Debian 12 → `agy-server_linux_{arm64|amd64}.tar.gz`
  (rilis GitHub) → `language_server` diekstrak dari `Antigravity.tar.gz` resmi (URL dipindai dari
  `antigravity.google/download`, persis seperti `install.sh` upstream). Setiap biner divalidasi header ELF
  (64-bit LE, `e_machine` sesuai CPU).
- **Provisioning** saat start pertama: `agy-server config --port 8765 --language-server /opt/agy-server/language_server
  --workspace-root /root/workspace` lalu `agy-server passwd <acak>`. Kata sandi tampil di Pengaturan.
- **Login**: layar login agy-server (kata sandi) → Pengaturan di UI web → Google sign-in. Popup OAuth memakai UA mirip
  Chrome; bila Google menolak WebView, tombol **Browser** di popup membuka peramban (callback ke `localhost` tetap
  sampai karena server berada di perangkat yang sama), atau **Pengaturan → Impor token login Google**
  (berkas `~/.gemini/jetski-standalone-oauth-token` dari desktop).
- **Keamanan**: akses web selalu berkas kata sandi; setelah server siap, app mengecek apakah port terjangkau dari LAN
  dan memperingatkan di Pengaturan.

## Build

JDK 17, Android SDK 34, Gradle 8.9 (wrapper disertakan). `./gradlew :app:assembleDebug` →
`app/build/outputs/apk/debug/app-debug.apk`. CI (`.github/workflows/build.yml`) menjalankan
`python3 tools/check_project.py` lalu Gradle. APK rilis: isi `keystore.properties` (lihat `app/build.gradle.kts`).

`targetSdk = 28` disengaja (sama dengan Termux): Android 10+ melarang `exec()` dari penyimpanan aplikasi untuk targetSdk ≥ 29.

## Status verifikasi — baca ini

Lingkungan pembuatan **tidak punya Android SDK/Gradle maupun emulator/perangkat**, jadi kode **belum pernah dikompilasi
atau dijalankan**. Yang sudah dicek: `tools/check_project.py` (XML valid, semua `R.*`/`@tipe/nama` ada, binding cocok id layout,
package = direktori, impor lintas-paket, kurung seimbang, kelas manifest ada) dan tinjauan manual terhadap kode CodeX Studio
yang sudah terbukti. Kerangka (proot, Archive, Net, WebView, popup, keyboard) dipakai ulang tanpa perubahan perilaku.

Hal yang **tidak bisa saya pastikan** dan perlu diuji di perangkat:
1. `language_server` Antigravity berjalan di bawah proot (ptrace) — performa/stabilitas belum teruji; memori bisa besar di HP RAM kecil.
2. Alamat bind default `agy-server serve` tidak terdokumentasi di README upstream; app mendeteksi paparan LAN, bukan mencegahnya.
3. Alur login Google lewat WebView/popup/peramban — Google sering menolak WebView; fallback impor token tersedia.
4. Nama aset rilis `agy-server_linux_<arch>.tar.gz` dan pola URL bundel mengikuti `install.sh` upstream per Oktober 2026; bila upstream mengubahnya, edit `Pins.kt`/`AgyServer.kt`.
5. ARMv7 32-bit tidak didukung (Antigravity hanya arm64/x86_64).

Langkah uji pertama: pasang APK → tunggu instalasi → Pengaturan → **Diagnostik (agy-server doctor)** → lihat **Lihat log**.
