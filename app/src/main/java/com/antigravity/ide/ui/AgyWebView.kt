package com.antigravity.ide.ui

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView

/**
 * WebView yang mencegat input dari keyboard layar (IME) supaya tombol volume
 * ala Termux (Vol-Bawah = Ctrl, Vol-Atas = Fn/Shift) dan modifier bar bisa
 * mengubah huruf yang diketik menjadi kombinasi tombol (mis. Ctrl+S).
 */
class AgyWebView(context: Context) : WebView(context) {
    var keyPad: KeyPad? = null

    /** true = keyboard virtual aktif: jangan munculkan keyboard sistem. */
    var suppressIme = false

    override fun onCheckIsTextEditor(): Boolean = !suppressIme && super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        // Matikan saran/koreksi otomatis di kolom teks biasa agar huruf dikirim satu per satu
        // (bukan composing text) -> modifier bisa diterapkan. Sama seperti Termux.
        val cls = outAttrs.inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = outAttrs.inputType and android.text.InputType.TYPE_MASK_VARIATION
        if (cls == android.text.InputType.TYPE_CLASS_TEXT && variation == 0) {
            outAttrs.inputType = outAttrs.inputType or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        return Wrapper(base)
    }

    private inner class Wrapper(target: InputConnection) : InputConnectionWrapper(target, true) {
        private val swallowed = HashSet<Int>()          // keyCode yang UP-nya harus dibuang
        private val upMods = HashMap<Int, Int>()        // keyCode -> modifier untuk UP

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val kp = keyPad
            if (kp != null && text.length == 1 && kp.hasPending() && kp.sendChar(text[0])) return true
            return super.commitText(text, newCursorPosition)
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val kp = keyPad
            if (kp != null && text.length == 1 && kp.hasPending() && kp.sendChar(text[0])) {
                super.finishComposingText()
                return true
            }
            return super.setComposingText(text, newCursorPosition)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val kp = keyPad ?: return super.sendKeyEvent(event)
            val code = event.keyCode
            if (event.action == KeyEvent.ACTION_UP) {
                if (swallowed.remove(code)) return true
                val m = upMods.remove(code)
                if (m != null) return super.sendKeyEvent(withMeta(event, m))
                return super.sendKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_DOWN && kp.hasPending()) {
                val ch = event.unicodeChar
                if (ch != 0 && !event.isCtrlPressed && kp.sendChar(ch.toChar())) {
                    swallowed.add(code)
                    return true
                }
                if (ch == 0) { // Enter, Backspace, Tab, panah, dst.: tambahkan modifier saja
                    val extra = kp.takeMetaForKey()
                    if (extra != 0) {
                        upMods[code] = extra
                        return super.sendKeyEvent(withMeta(event, extra))
                    }
                }
            }
            return super.sendKeyEvent(event)
        }

        private fun withMeta(e: KeyEvent, extra: Int) = KeyEvent(
            e.downTime, e.eventTime, e.action, e.keyCode, e.repeatCount,
            e.metaState or extra, e.deviceId, e.scanCode, e.flags, e.source
        )
    }
}
