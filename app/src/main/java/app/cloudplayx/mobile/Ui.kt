package app.cloudplayx.mobile

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Colours for the dark and light variants of the CloudPlayX look (navy + violet + mint). Follows the system setting. */
class Palette(val dark: Boolean) {
    val bg = if (dark) Color.rgb(10, 15, 30) else Color.rgb(244, 246, 252)
    val panel = if (dark) Color.rgb(21, 29, 49) else Color.WHITE
    val panelAlt = if (dark) Color.rgb(43, 58, 88) else Color.rgb(222, 228, 244)
    val accent = Color.rgb(104, 72, 235)
    val mint = if (dark) Color.rgb(55, 211, 177) else Color.rgb(0, 122, 100)
    val onMint = if (dark) Color.rgb(10, 15, 30) else Color.WHITE
    val text = if (dark) Color.rgb(244, 247, 255) else Color.rgb(20, 27, 45)
    val muted = if (dark) Color.rgb(166, 177, 202) else Color.rgb(78, 89, 116)
    val danger = if (dark) Color.rgb(255, 145, 145) else Color.rgb(176, 32, 48)
    val dangerFill = if (dark) Color.rgb(120, 48, 70) else Color.rgb(168, 40, 62)
    val warn = if (dark) Color.rgb(255, 206, 110) else Color.rgb(143, 86, 0)
    val ok = if (dark) Color.rgb(110, 231, 183) else Color.rgb(0, 112, 80)
}

enum class BtnKind { PRIMARY, MINT, SECONDARY, DANGER }

/** Small view factory shared by all screens, so spacing, colours and touch-target sizes stay consistent. */
class Ui(val ctx: Context) {
    val p = Palette((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun label(text: CharSequence, size: Float = 14f, color: Int = p.text, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(p.panel, 20)
    }

    fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    fun button(text: String, kind: BtnKind = BtnKind.SECONDARY, enabled: Boolean = true, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        val (fill, fg) = when (kind) {
            BtnKind.PRIMARY -> p.accent to Color.WHITE
            BtnKind.MINT -> p.mint to p.onMint
            BtnKind.SECONDARY -> p.panelAlt to p.text
            BtnKind.DANGER -> p.dangerFill to Color.WHITE
        }
        setTextColor(fg)
        backgroundTintList = ColorStateList.valueOf(fill)
        minHeight = dp(48)
        minimumHeight = dp(48)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { _: View -> onClick() }
        layoutParams = margins(top = 8)
    }

    fun setEnabled(b: Button, enabled: Boolean) { b.isEnabled = enabled; b.alpha = if (enabled) 1f else 0.45f }

    fun field(hint: String, value: String, inputType: Int) = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        this.inputType = inputType
        setTextColor(p.text)
        setHintTextColor(p.muted)
        textSize = 14f
        setSingleLine(true)
        backgroundTintList = ColorStateList.valueOf(p.muted)
        minHeight = dp(48)
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    fun progress(max: Int = 1000) = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        this.max = max
        progressTintList = ColorStateList.valueOf(p.mint)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6); bottomMargin = dp(4) }
    }

    /** Status/navigation bar colours that match the screen. */
    @Suppress("DEPRECATION")
    fun styleWindow(a: Activity) {
        a.window.statusBarColor = p.bg
        a.window.navigationBarColor = p.bg
        var flags = 0
        if (!p.dark) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        a.window.decorView.systemUiVisibility = flags
    }

    fun toast(message: String, long: Boolean = false) =
        Toast.makeText(ctx, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Runs [block] on the UI thread unless the screen is already gone. */
fun Activity.ui(block: () -> Unit) {
    runOnUiThread { if (!isFinishing && !isDestroyed) block() }
}

object Fmt {
    fun speed(bps: Long) = if (bps < 0) "" else GameCompat.human(bps) + "/s"

    fun eta(sec: Long): String = when {
        sec < 0 -> ""
        sec < 60 -> "${sec}s left"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s left"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m left"
    }

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
}

/** Runs [block] on a plain background thread (network and disk work must never run on the main thread). */
fun runBg(block: () -> Unit) {
    Thread({
        try { block() } catch (_: Throwable) { }
    }, "cpx-bg").start()
}
