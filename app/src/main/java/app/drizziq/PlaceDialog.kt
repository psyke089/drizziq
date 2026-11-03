package app.drizziq

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** The X uses cancel(), exactly like Back/outside dismissal, never an affirmative callback. */
internal fun Activity.placeDialog(builder: AlertDialog.Builder, title: CharSequence): AlertDialog {
    val header =
        FrameLayout(this).apply {
            minimumHeight = dp(64)
            setPadding(dp(20), dp(8), dp(8), 0)
        }
    header.addView(
        label(title.toString(), 18f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        },
        FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { rightMargin = dp(56) },
    )
    val close =
        FrameLayout(this).apply {
            contentDescription = "Close dialog"
            isClickable = true
            isFocusable = true
            addView(
                SvgIcon(this@placeDialog, "close").apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER),
            )
        }
    header.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
    val dialog = builder.setCustomTitle(header).create()
    close.setOnClickListener { dialog.cancel() }
    return dialog
}
