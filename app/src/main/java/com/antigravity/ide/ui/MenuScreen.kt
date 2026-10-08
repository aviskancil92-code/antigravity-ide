package com.antigravity.ide.ui

import com.antigravity.ide.R
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch

/** Aksi yang dibutuhkan menu; diimplementasikan oleh MainActivity. */
interface MenuHost {
    fun restartServer()
    fun stopServer()
    var keyboardOpen: Boolean
    var volumeKeysOn: Boolean
    var uiScalePercent: Int
    var keepScreenOn: Boolean
    fun openSettings()
    fun openLogs()
    fun openAbout()
}

/**
 * Menu layar penuh bergaya iOS (daftar berkelompok, judul besar, tombol Kembali).
 * Dibuka dengan ketuk 3 jari.
 */
class MenuScreen(private val ctx: Context, private val host: MenuHost, private val onClose: () -> Unit) {

    val view: FrameLayout = FrameLayout(ctx)

    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun color(id: Int) = ContextCompat.getColor(ctx, id)

    init {
        view.setBackgroundColor(color(R.color.grouped_bg))
        view.isClickable = true
        view.isFocusable = false

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(4), px(16), px(32))
        }

        // Bilah atas: ‹ Kembali
        val back = TextView(ctx).apply {
            text = "‹  ${ctx.getString(R.string.menu_back)}"
            setTextColor(color(R.color.accent_blue))
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), 0, px(16), 0)
            setOnClickListener { onClose() }
        }
        content.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52)))

        content.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.menu_title)
            setTextColor(color(R.color.text_primary))
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(px(8), px(4), 0, px(12))
        })

        // --- SERVER
        content.addView(header(R.string.menu_section_server))
        content.addView(card(
            actionRow("↻", 0xFF3B82F6.toInt(), ctx.getString(R.string.menu_restart)) { host.restartServer(); onClose() },
            actionRow("■", 0xFFEF4444.toInt(), ctx.getString(R.string.menu_stop)) { host.stopServer(); onClose() }
        ))

        // --- INPUT
        content.addView(header(R.string.menu_section_input))
        content.addView(card(
            switchRow("⌨", 0xFF6366F1.toInt(), ctx.getString(R.string.menu_keyboard),
                ctx.getString(R.string.menu_keyboard_sub), host.keyboardOpen) { on ->
                host.keyboardOpen = on
                if (on) onClose()
            },
            switchRow("◐", 0xFF10B981.toInt(), ctx.getString(R.string.menu_volume),
                ctx.getString(R.string.menu_volume_sub), host.volumeKeysOn) { host.volumeKeysOn = it }
        ))

        // --- TAMPILAN
        content.addView(header(R.string.menu_section_display))
        content.addView(card(
            scaleRow(),
            switchRow("☀", 0xFFF59E0B.toInt(), ctx.getString(R.string.menu_keep_on), null, host.keepScreenOn) {
                host.keepScreenOn = it
            }
        ))

        // --- LAINNYA
        content.addView(header(R.string.menu_section_more))
        content.addView(card(
            actionRow("⚙", 0xFF8E8E93.toInt(), ctx.getString(R.string.settings), chevron = true) { host.openSettings() },
            actionRow("≡", 0xFF64748B.toInt(), ctx.getString(R.string.logs), chevron = true) { host.openLogs() },
            actionRow("i", 0xFF3B82F6.toInt(), ctx.getString(R.string.about), chevron = true) { host.openAbout() }
        ))

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        view.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // Hormati notch / bar sistem.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(160).start()
    }

    // ------------------------------------------------------------------ builder

    private fun header(res: Int) = TextView(ctx).apply {
        text = ctx.getString(res)
        setTextColor(color(R.color.text_secondary))
        textSize = 13f
        letterSpacing = 0.04f
        setPadding(px(16), px(20), 0, px(6))
    }

    private fun card(vararg rows: View): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color(R.color.bg_card))
                cornerRadius = 14 * dp
            }
            clipToOutline = true
        }
        for ((i, r) in rows.withIndex()) {
            if (i > 0) c.addView(View(ctx).apply { setBackgroundColor(color(R.color.separator)) },
                LinearLayout.LayoutParams(-1, 1).apply { marginStart = px(58) })
            c.addView(r)
        }
        return c
    }

    private fun iconTile(glyph: String, bg: Int) = TextView(ctx).apply {
        text = glyph
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { setColor(bg); cornerRadius = 8 * dp }
        layoutParams = LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(14) }
    }

    private fun row(glyph: String, tile: Int, title: String, sub: String?): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(52)
            setPadding(px(16), px(8), px(16), px(8))
        }
        r.addView(iconTile(glyph, tile))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title
            setTextColor(color(R.color.text_primary))
            textSize = 17f
        })
        if (sub != null) col.addView(TextView(ctx).apply {
            text = sub
            setTextColor(color(R.color.text_secondary))
            textSize = 12.5f
        })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnClickListener { onClick() }
        v.foreground = ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background)?.also {
            it.alpha = 40
        }
    }

    private fun actionRow(glyph: String, tile: Int, title: String, chevron: Boolean = false, onClick: () -> Unit): View {
        val r = row(glyph, tile, title, null)
        if (chevron) r.addView(TextView(ctx).apply {
            text = "›"
            setTextColor(color(R.color.text_secondary))
            textSize = 24f
        })
        pressable(r, onClick)
        return r
    }

    private fun switchRow(glyph: String, tile: Int, title: String, sub: String?, checked: Boolean,
                          onChange: (Boolean) -> Unit): View {
        val r = row(glyph, tile, title, sub)
        val sw = MaterialSwitch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        r.addView(sw)
        r.isClickable = true
        r.setOnClickListener { sw.toggle() }
        return r
    }

    private fun scaleRow(): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(iconTile("Aa", 0xFF0EA5E9.toInt()))
        top.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.menu_scale)
            setTextColor(color(R.color.text_primary))
            textSize = 17f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(ctx).apply {
            setTextColor(color(R.color.text_secondary))
            textSize = 15f
        }
        top.addView(value)
        wrap.addView(top)

        // 50%..200% langkah 5%
        val min = 50
        val bar = SeekBar(ctx).apply {
            max = (200 - min) / 5
            progress = (host.uiScalePercent.coerceIn(min, 200) - min) / 5
        }
        fun label(p: Int) { value.text = "$p%" }
        label(host.uiScalePercent)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val pct = min + p * 5
                label(pct)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                host.uiScalePercent = min + s.progress * 5 // terapkan saat jari diangkat
            }
        })
        wrap.addView(bar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(6)
        })
        wrap.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.menu_scale_reset)
            setTextColor(color(R.color.accent_blue))
            textSize = 15f
            setPadding(0, px(6), 0, px(2))
            setOnClickListener {
                bar.progress = (100 - min) / 5
                host.uiScalePercent = 100
                label(100)
            }
        })
        return wrap
    }
}
