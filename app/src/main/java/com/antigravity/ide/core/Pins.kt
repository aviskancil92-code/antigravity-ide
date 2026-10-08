package com.antigravity.ide.core

/**
 * Pin sumber unduhan. Semua komponen diunduh saat instalasi pertama (APK tetap kecil).
 *
 *  - proot (+ libtalloc, libandroid-shmem): repo apt Termux (biner Android/bionic).
 *  - rootfs Debian: images.linuxcontainers.org.
 *  - agy-server: rilis GitHub AFSlayer/antigravity-server (satu biner Go, tanpa dependensi).
 *  - language_server: bundel resmi Antigravity dari bucket Google (sama seperti install.sh upstream;
 *    tidak ada biner Google yang didistribusikan ulang di dalam APK ini).
 */
object Pins {

    /** Repo apt Termux utama + mirror cadangan (untuk biner proot). */
    const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main/"
    const val TERMUX_REPO_MIRROR = "https://packages-cf.termux.dev/apt/termux-main/"

    /** Rilis Debian untuk rootfs (bookworm = Debian 12, stabil). */
    const val DEBIAN_RELEASE = "bookworm"

    /** Server citra resmi LXC — menyediakan rootfs Debian per-arsitektur. */
    const val LXC_BASE = "https://images.linuxcontainers.org/images/debian/"

    /** Rilis terbaru agy-server; nama aset: agy-server_linux_{amd64|arm64}.tar.gz. */
    const val AGY_RELEASE_BASE = "https://github.com/AFSlayer/antigravity-server/releases/latest/download/"

    /** Halaman unduhan resmi Antigravity — dipindai untuk URL bundel per-platform. */
    const val ANTIGRAVITY_DOWNLOAD_PAGE = "https://antigravity.google/download"

    /** UA mirip curl: install.sh upstream memakai curl, sehingga halaman unduhan terbukti melayani UA ini. */
    const val DOWNLOAD_PAGE_UA = "curl/8.5.0"

    fun agyServerUrl(agyArch: String): String =
        "${AGY_RELEASE_BASE}agy-server_linux_$agyArch.tar.gz"
}
