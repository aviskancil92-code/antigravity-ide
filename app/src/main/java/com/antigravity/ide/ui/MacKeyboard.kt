package com.antigravity.ide.ui

import com.antigravity.ide.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Keyboard virtual bergaya keyboard Mac gelap (esc/F1–F12, deret angka, tab, caps, shift,
 * fn/control/option/command, panah). Berukuran proporsional terhadap lebar layar.
 *
 * - Shift / Control / Option / Command: ketuk = aktif untuk 1 tombol, tekan lama = kunci.
 * - Command diperlakukan sebagai Ctrl (VS Code di Android memakai pintasan gaya Linux),
 *   jadi ⌘S / ⌘C / ⌘V / ⌘P bekerja seperti di Mac.
 * - fn + panah = Home / End / PgUp / PgDn, fn + delete = hapus maju.
 */
class MacKeyboard(
    context: Context,
    private val pad: KeyPad,
    private val widthPx: Int,
    private val maxHeightPx: Int
) : LinearLayout(context) {

    private enum class Kind { CHAR, SHIFT, CTRL, OPT, CMD, CAPS, FN }

    private class K(
        val main: String,
        val top: String? = null,
        val code: Int = 0,
        val w: Float = 1f,
        val kind: Kind = Kind.CHAR,
        val repeat: Boolean = false,
        val small: Boolean = false
    )

    private val dp = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val cBg = ContextCompat.getColor(context, R.color.kb_bg)
    private val cKey = ContextCompat.getColor(context, R.color.kb_key)
    private val cMod = ContextCompat.getColor(context, R.color.kb_key_mod)
    private val cPressed = ContextCompat.getColor(context, R.color.kb_key_pressed)
    private val cActive = ContextCompat.getColor(context, R.color.kb_active)
    private val cLocked = ContextCompat.getColor(context, R.color.kb_locked)
    private val cText = ContextCompat.getColor(context, R.color.kb_text)
    private val cDim = ContextCompat.getColor(context, R.color.kb_text_dim)

    private val changedCb: () -> Unit = { post { refresh() } }
    private var caps = false
    private var fn = false

    private val modViews = ArrayList<Pair<K, TextView>>()
    private val rowWeights = floatArrayOf(0.72f, 1f, 1f, 1f, 1f, 1.08f)

    init {
        orientation = VERTICAL
        setBackgroundColor(cBg)
        val pad4 = (6 * dp).toInt()
        setPadding(pad4, (8 * dp).toInt(), pad4, (8 * dp).toInt() + 2)
        isClickable = true
        build()
        pad.onChanged = changedCb
    }

    /** Tinggi keyboard: proporsional lebar, dibatasi agar editor tetap terlihat. */
    fun targetHeight(): Int {
        val rows = rowWeights.sum()
        val byWidth = (widthPx * 0.083f * rows).toInt()
        return minOf(byWidth, maxHeightPx) + (16 * dp).toInt()
    }

    // ------------------------------------------------------------------ layout

    private fun letters(s: String) = s.map { c ->
        K(c.toString(), code = KeyEvent.KEYCODE_A + (c - 'A'))
    }

    private fun layout(): List<List<K>> {
        val f = (1..12).map { n -> K("F$n", code = KeyEvent.KEYCODE_F1 + n - 1, small = true) }
        val r0 = listOf(K("esc", code = KeyEvent.KEYCODE_ESCAPE, w = 1.3f, small = true)) + f +
            listOf(K("⌦", code = KeyEvent.KEYCODE_FORWARD_DEL, w = 1.3f, repeat = true))
        val nums = listOf(
            K("`", "~", KeyEvent.KEYCODE_GRAVE),
            K("1", "!", KeyEvent.KEYCODE_1), K("2", "@", KeyEvent.KEYCODE_2),
            K("3", "#", KeyEvent.KEYCODE_3), K("4", "$", KeyEvent.KEYCODE_4),
            K("5", "%", KeyEvent.KEYCODE_5), K("6", "^", KeyEvent.KEYCODE_6),
            K("7", "&", KeyEvent.KEYCODE_7), K("8", "*", KeyEvent.KEYCODE_8),
            K("9", "(", KeyEvent.KEYCODE_9), K("0", ")", KeyEvent.KEYCODE_0),
            K("-", "_", KeyEvent.KEYCODE_MINUS), K("=", "+", KeyEvent.KEYCODE_EQUALS),
            K("delete", code = KeyEvent.KEYCODE_DEL, w = 1.7f, repeat = true, small = true)
        )
        val r2 = listOf(K("tab", code = KeyEvent.KEYCODE_TAB, w = 1.5f, small = true)) +
            letters("QWERTYUIOP") + listOf(
            K("[", "{", KeyEvent.KEYCODE_LEFT_BRACKET),
            K("]", "}", KeyEvent.KEYCODE_RIGHT_BRACKET),
            K("\\", "|", KeyEvent.KEYCODE_BACKSLASH, w = 1.2f)
        )
        val r3 = listOf(K("caps", w = 1.8f, kind = Kind.CAPS, small = true)) +
            letters("ASDFGHJKL") + listOf(
            K(";", ":", KeyEvent.KEYCODE_SEMICOLON),
            K("'", "\"", KeyEvent.KEYCODE_APOSTROPHE),
            K("return", code = KeyEvent.KEYCODE_ENTER, w = 2.1f, small = true)
        )
        val r4 = listOf(K("shift", w = 2.3f, kind = Kind.SHIFT, small = true)) +
            letters("ZXCVBNM") + listOf(
            K(",", "<", KeyEvent.KEYCODE_COMMA),
            K(".", ">", KeyEvent.KEYCODE_PERIOD),
            K("/", "?", KeyEvent.KEYCODE_SLASH),
            K("shift", w = 2.3f, kind = Kind.SHIFT, small = true)
        )
        return listOf(r0, nums, r2, r3, r4)
    }

    private fun build() {
        removeAllViews()
        modViews.clear()
        val rows = layout()
        for ((i, row) in rows.withIndex()) addRow(row, rowWeights[i])
        addBottomRow()
        refresh()
    }

    private fun addRow(keys: List<K>, weight: Float) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (k in keys) row.addView(makeKey(k), keyParams(k.w))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, weight))
    }

    private fun keyParams(w: Float): LayoutParams =
        LayoutParams(0, LayoutParams.MATCH_PARENT, w).apply {
            val m = (2 * dp).toInt()
            setMargins(m, m, m, m)
        }

    private fun addBottomRow() {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val left = listOf(
            K("fn", w = 1.15f, kind = Kind.FN, small = true),
            K("control", w = 1.3f, kind = Kind.CTRL, small = true),
            K("option", w = 1.3f, kind = Kind.OPT, small = true),
            K("⌘", w = 1.5f, kind = Kind.CMD)
        )
        for (k in left) row.addView(makeKey(k), keyParams(k.w))
        row.addView(makeKey(K("", code = KeyEvent.KEYCODE_SPACE, w = 5.6f, repeat = true)), keyParams(5.6f))
        row.addView(makeKey(K("⌘", w = 1.5f, kind = Kind.CMD)), keyParams(1.5f))
        row.addView(makeKey(K("option", w = 1.3f, kind = Kind.OPT, small = true)), keyParams(1.3f))
        // Panah: ◀ [▲/▼] ▶
        row.addView(makeKey(K("◀", code = KeyEvent.KEYCODE_DPAD_LEFT, w = 1f, repeat = true)), keyParams(1f))
        val ud = LinearLayout(context).apply { orientation = VERTICAL }
        val half = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            val m = (1 * dp).toInt(); setMargins(0, m, 0, m)
        }
        ud.addView(makeKey(K("▲", code = KeyEvent.KEYCODE_DPAD_UP, repeat = true, small = true)), half)
        ud.addView(makeKey(K("▼", code = KeyEvent.KEYCODE_DPAD_DOWN, repeat = true, small = true)), half)
        row.addView(ud, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            val m = (2 * dp).toInt(); setMargins(m, m, m, m)
        })
        row.addView(makeKey(K("▶", code = KeyEvent.KEYCODE_DPAD_RIGHT, w = 1f, repeat = true)), keyParams(1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, rowWeights[5]))
    }

    // ------------------------------------------------------------------- tombol

    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 7 * dp
    }

    private fun keyBg(base: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(cPressed))
        addState(intArrayOf(), shape(base))
    }

    private fun unit() = widthPx / 14.6f

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(k: K): TextView {
        val tv = TextView(context)
        tv.gravity = Gravity.CENTER
        tv.isFocusable = false
        tv.isFocusableInTouchMode = false
        tv.setTextColor(cText)
        tv.includeFontPadding = false
        val isMod = k.kind != Kind.CHAR
        tv.background = keyBg(if (isMod || k.small) cMod else cKey)
        val main = if (k.small || isMod) unit() * 0.30f else unit() * 0.44f
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (k.main.length > 1 && (k.small || isMod)) main else main)
        if (k.top != null) {
            val s = SpannableString("${k.top}\n${k.main}")
            s.setSpan(RelativeSizeSpan(0.72f), 0, k.top.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = s
            tv.setLineSpacing(0f, 0.92f)
        } else {
            tv.text = k.main
        }
        if (k.code == KeyEvent.KEYCODE_SPACE) tv.contentDescription = "space"
        if (isMod) modViews.add(k to tv)

        var longFired = false
        val longRun = Runnable {
            longFired = true
            when (k.kind) {
                Kind.SHIFT -> pad.toggleLock(KeyPad.S)
                Kind.CTRL -> pad.toggleLock(KeyPad.C)
                Kind.OPT -> pad.toggleLock(KeyPad.A)
                Kind.CMD -> pad.toggleLock(KeyPad.M)
                else -> {}
            }
            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        val repeatRun = object : Runnable {
            override fun run() {
                fire(k)
                handler.postDelayed(this, 65)
            }
        }
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (isMod) {
                        longFired = false
                        handler.postDelayed(longRun, 450)
                    } else {
                        fire(k)
                        if (k.repeat) handler.postDelayed(repeatRun, 380)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeatRun)
                    if (isMod) {
                        handler.removeCallbacks(longRun)
                        if (!longFired) tap(k)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeatRun)
                    handler.removeCallbacks(longRun)
                }
            }
            true
        }
        return tv
    }

    private fun tap(k: K) {
        when (k.kind) {
            Kind.SHIFT -> pad.toggleOneShot(KeyPad.S)
            Kind.CTRL -> pad.toggleOneShot(KeyPad.C)
            Kind.OPT -> pad.toggleOneShot(KeyPad.A)
            Kind.CMD -> pad.toggleOneShot(KeyPad.M)
            Kind.CAPS -> { caps = !caps; refresh() }
            Kind.FN -> { fn = !fn; refresh() }
            else -> {}
        }
    }

    private fun fire(k: K) {
        if (k.kind != Kind.CHAR) return
        var code = k.code
        if (fn) {
            val mapped = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_MOVE_HOME
                KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_MOVE_END
                KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_PAGE_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
                KeyEvent.KEYCODE_DEL -> KeyEvent.KEYCODE_FORWARD_DEL
                else -> code
            }
            if (mapped != code) {
                code = mapped
            }
            fn = false
            refresh()
        }
        val letter = code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        pad.press(code, if (caps && letter) KeyPad.S else 0)
    }

    /** Perbarui warna modifier sesuai status (aktif / terkunci). */
    fun refresh() {
        for ((k, tv) in modViews) {
            val st = when (k.kind) {
                Kind.SHIFT -> pad.state(KeyPad.S)
                Kind.CTRL -> pad.state(KeyPad.C)
                Kind.OPT -> pad.state(KeyPad.A)
                Kind.CMD -> pad.state(KeyPad.M)
                Kind.CAPS -> if (caps) 2 else 0
                Kind.FN -> if (fn) 1 else 0
                else -> 0
            }
            val base = when (st) {
                2 -> cLocked
                1 -> cActive
                else -> cMod
            }
            tv.background = keyBg(base)
            tv.setTextColor(if (st == 0) cText else 0xFF0B0D12.toInt())
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        if (pad.onChanged === changedCb) pad.onChanged = null
        super.onDetachedFromWindow()
    }
}
