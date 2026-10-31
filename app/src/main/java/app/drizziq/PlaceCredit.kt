package app.drizziq

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView

fun Context.placeCreditText(place: Place? = null): CharSequence {
    val text =
        getString(
            R.string.place_attribution,
            if (place?.geocodeLabelEdited == true) getString(R.string.place_label_edited) else "",
        )
    return SpannableString(text).apply {
        for ((title, url) in
            listOf(
                "Open-Meteo" to "https://open-meteo.com/",
                "GeoNames" to "https://www.geonames.org/",
            )) {
            val start = text.indexOf(title)
            setSpan(URLSpan(url), start, start + title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

fun Context.placeCreditView(place: Place? = null): TextView =
    label("", 10f, Hud.muted).apply {
        text = placeCreditText(place)
        movementMethod = LinkMovementMethod.getInstance()
        setLinkTextColor(Hud.cyan)
        setPadding(0, context.dp(4), 0, context.dp(4))
    }
