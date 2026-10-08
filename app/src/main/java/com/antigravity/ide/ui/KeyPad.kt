package com.antigravity.ide.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebView

/**
 * Inti injeksi tombol untuk code-server di WebView:
 *  - keyboard virtual (MacKeyboard) memanggil press()
 *  - tombol volume ala Termux (Vol-Bawah = Ctrl, Vol-Atas = Fn)
 *  - CodeWebView memanggil sendChar()/takeMetaForKey() untuk mengubah ketikan IME
 *    menjadi kombinasi tombol saat modifier aktif.
 */
class KeyPad(
    private val target: () -> WebView?,
    private val onToggleKeyboard: () -> Unit = {}
) {
    companion object {
        const val C = 1  // Ctrl
        const val S = 2  // Shift
        const val A = 4  // Alt / Option
        const val M = 8  // Command (diperlakukan sebagai Ctrl: VS Code di Android memakai pintasan Linux)
    }

    /** Modifier untuk 1 ketukan berikutnya / dikunci (diatur keyboard virtual). */
    var oneShot = 0
    var locked = 0
    var onChanged: (() -> Unit)? = null

    /** true = tombol volume aktif sebagai Ctrl/Fn. */
    var volumeKeys = true

    private var volDown = false
    private var volUp = false
    private var volDownUsed = false
    private var volUpUsed = false
    private var fnOnce = false

    private fun changed() { onChanged?.invoke() }

    // ------------------------------------------------------------- modifier

    private fun metaOf(m: Int): Int {
        var r = 0
        if ((m and (C or M)) != 0) r = r or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if ((m and S) != 0) r = r or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if ((m and A) != 0) r = r or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return r
    }

    fun toggleOneShot(flag: Int) {
        if ((locked and flag) != 0) locked = locked and flag.inv() else oneShot = oneShot xor flag
        changed()
    }

    fun toggleLock(flag: Int) {
        locked = locked xor flag
        oneShot = oneShot and flag.inv()
        changed()
    }

    /** 0 = mati, 1 = aktif sekali, 2 = terkunci. */
    fun state(flag: Int): Int {
        val vol = if (volumeKeys && volDown) C else 0
        return when {
            (locked and flag) != 0 -> 2
            ((oneShot or vol) and flag) != 0 -> 1
            else -> 0
        }
    }

    private fun fnActive() = volumeKeys && (volUp || fnOnce)

    private fun heldMods(): Int = (if (volumeKeys && volDown) C else 0) or oneShot or locked

    fun hasPending() = heldMods() != 0 || fnActive()

    private fun markUsed() {
        if (volDown) volDownUsed = true
        if (volUp) volUpUsed = true
    }

    private fun consume() {
        markUsed()
        oneShot = 0
        fnOnce = false
        changed()
    }

    // ---------------------------------------------------------- tombol volume

    /** Dipanggil dari Activity.dispatchKeyEvent. true = tombol volume dikonsumsi. */
    fun onVolume(keyCode: Int, down: Boolean, repeat: Int): Boolean {
        if (!volumeKeys) return false
        val isDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (down) {
            if (repeat == 0) {
                if (isDown) { volDown = true; volDownUsed = false } else { volUp = true; volUpUsed = false }
                changed()
            }
            return true
        }
        if (isDown) {
            volDown = false
            if (!volDownUsed) oneShot = oneShot xor C   // ketuk saja = Ctrl untuk tombol berikutnya
        } else {
            volUp = false
            if (!volUpUsed) fnOnce = !fnOnce
        }
        changed()
        return true
    }

    // --------------------------------------------------------- kirim tombol

    fun pressRaw(code: Int, mods: Int) {
        val wv = target() ?: return
        wv.requestFocus()
        val meta = metaOf(mods)
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int) = KeyEvent(
            t, t, action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        wv.dispatchKeyEvent(ev(KeyEvent.ACTION_DOWN))
        wv.dispatchKeyEvent(ev(KeyEvent.ACTION_UP))
    }

    /** Tekan tombol dengan modifier tambahan; modifier sekali-pakai ikut diterapkan lalu dibersihkan. */
    fun press(code: Int, mods: Int = 0) {
        pressRaw(code, mods or heldMods())
        consume()
    }

    private fun typeRaw(text: String) {
        val wv = target() ?: return
        wv.requestFocus()
        wv.onCreateInputConnection(EditorInfo())?.commitText(text, 1)
    }

    /** Modifier untuk tombol non-karakter dari IME (Enter, Backspace, panah). */
    fun takeMetaForKey(): Int {
        val m = heldMods()
        if (m == 0) return 0
        consume()
        return metaOf(m)
    }

    /**
     * Terapkan modifier / lapisan Fn pada satu karakter yang diketik lewat IME.
     * true = karakter sudah ditangani (jangan commit sebagai teks biasa).
     */
    fun sendChar(c: Char): Boolean {
        val fn = fnActive()
        val mods = heldMods()
        if (!fn && mods == 0) return false
        if (fn) {
            val action = fnTable(c.lowercaseChar())
            if (action != null) {
                consume()
                action(mods)
                return true
            }
        }
        if (mods == 0) { consume(); return false }
        val evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(charArrayOf(c))
        consume()
        if (evs == null) return false
        val wv = target() ?: return true
        val extra = metaOf(mods)
        val t = SystemClock.uptimeMillis()
        for (e in evs) {
            wv.dispatchKeyEvent(
                KeyEvent(
                    t, t, e.action, e.keyCode, 0, e.metaState or extra,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                )
            )
        }
        return true
    }

    private fun fnTable(c: Char): ((Int) -> Unit)? {
        fun act(f: (Int) -> Unit): (Int) -> Unit = f
        fun key(code: Int): (Int) -> Unit = act { m -> pressRaw(code, m) }
        return when (c) {
            'w' -> key(KeyEvent.KEYCODE_DPAD_UP)
            'a' -> key(KeyEvent.KEYCODE_DPAD_LEFT)
            's' -> key(KeyEvent.KEYCODE_DPAD_DOWN)
            'd' -> key(KeyEvent.KEYCODE_DPAD_RIGHT)
            'e' -> key(KeyEvent.KEYCODE_ESCAPE)
            't' -> key(KeyEvent.KEYCODE_TAB)
            'p' -> key(KeyEvent.KEYCODE_PAGE_UP)
            'n' -> key(KeyEvent.KEYCODE_PAGE_DOWN)
            'i' -> key(KeyEvent.KEYCODE_MOVE_HOME)
            'o' -> key(KeyEvent.KEYCODE_MOVE_END)
            'x' -> key(KeyEvent.KEYCODE_FORWARD_DEL)
            'h' -> act { typeRaw("~") }
            'u' -> act { typeRaw("_") }
            'l' -> act { typeRaw("|") }
            '.' -> act { m -> pressRaw(KeyEvent.KEYCODE_BACKSLASH, m or C) }
            'q' -> act { onToggleKeyboard() }
            in '1'..'9' -> key(KeyEvent.KEYCODE_F1 + (c - '1'))
            '0' -> key(KeyEvent.KEYCODE_F10)
            else -> null
        }
    }
}
