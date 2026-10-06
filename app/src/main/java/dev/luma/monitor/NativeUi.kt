package dev.luma.monitor

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.previewHeight() = dp(kotlin.math.ceil(300 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt())

internal fun Context.column(padding: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

internal fun Context.label(value: String, size: Float = 14f, muted: Boolean = false, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(getColor(if (muted) R.color.muted else R.color.ink))
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setLineSpacing(dp(3).toFloat(), 1f)
}

internal fun Context.button(value: String, outlined: Boolean = false, action: () -> Unit) =
    MaterialButton(this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
        text = value
        isAllCaps = false
        cornerRadius = dp(14)
        minimumHeight = dp(52)
        setOnClickListener { action() }
    }

internal fun LinearLayout.add(view: View, top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = context.dp(top) })
}

internal fun Context.panel(): LinearLayout = column(20).apply {
    background = GradientDrawable().apply {
        setColor(getColor(R.color.surface)); cornerRadius = dp(22).toFloat()
        setStroke(dp(1), getColor(R.color.stroke))
    }
}

internal fun AppCompatActivity.installRoot(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    setContentView(root)
    WindowCompat.getInsetsController(window, root).apply {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    root.setBackgroundColor(getColor(R.color.page))
    ViewCompat.requestApplyInsets(root)
}

internal fun Context.cardPreview(): View {
    val config = CardConfig("demo", "Tokyo · 01", 1, "香港探测点", demo = true)
    // An AppCompat Activity's inflater replaces ImageView with AppCompatImageView.
    // Its overridden setImageBitmap is not a RemoteViews method on Android 16.
    // Inflate with an application-backed context, as the launcher does, so all
    // widget views remain framework classes even when previewed inside the app.
    val previewContext = android.view.ContextThemeWrapper(applicationContext, R.style.Theme_Luma)
    val preview = LumaWidgetProvider.views(previewContext, -99, config, CardSnapshot.demo(), true)
        .apply(previewContext, null)
    preview.findViewById<View>(R.id.card_root).setOnClickListener(null)
    preview.findViewById<View>(R.id.refresh).setOnClickListener(null)
    return preview
}
