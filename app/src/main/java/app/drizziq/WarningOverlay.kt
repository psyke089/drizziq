package app.drizziq

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Spinner

/** The language controls stay in place while the warning text is replaced below them. */
internal class WarningOverlay(
    context: Context,
    language: WarningLanguage,
    select: (WarningLanguage) -> Unit,
) {
    val view =
        context.column().apply { setPadding(context.dp(20), context.dp(4), context.dp(20), 0) }
    private val body = context.column().apply { setPadding(0, 0, 0, context.dp(16)) }
    private var displayed: Display? = null

    private data class Display(
        val warnings: List<DwdWarning>,
        val texts: List<WarningText>,
        val note: String?,
        val zone: String,
    )

    val selector =
        Spinner(context, Spinner.MODE_DROPDOWN).apply {
            val languages = WarningLanguage.entries
            contentDescription = context.getString(R.string.warning_language)
            setPopupBackgroundDrawable(panel())
            adapter =
                object :
                    ArrayAdapter<String>(
                        context,
                        android.R.layout.simple_spinner_item,
                        languages.map { it.title },
                    ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                        context.label(languages[position].title, 12f).apply {
                            minimumHeight = context.dp(30)
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(context.dp(8), 0, context.dp(8), 0)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            background = panel(Hud.bg, Hud.line, context.dp(8).toFloat())
                        }

                    override fun getDropDownView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup,
                    ) =
                        context.label(languages[position].title, 13f).apply {
                            minimumHeight = context.dp(44)
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(context.dp(12), 0, context.dp(12), 0)
                        }
                }
            setSelection(language.ordinal)
            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) = select(languages[position])

                    override fun onNothingSelected(parent: AdapterView<*>) = Unit
                }
        }

    init {
        view.addView(ScrollView(context).apply { addView(body) })
    }

    fun bind(
        language: WarningLanguage,
        warnings: List<DwdWarning>,
        translations: List<DwdWarning>,
        loading: Boolean,
        zone: String,
    ) {
        val byId = translations.associateBy { it.id }
        val localized = warnings.map { warning ->
            warning.id?.let { byId[it]?.translations?.get(language) }
        }
        val note =
            when {
                language == WarningLanguage.GERMAN || localized.all { it != null } -> null
                loading -> view.context.getString(R.string.warning_translation_loading)
                else -> view.context.getString(R.string.warning_translation_fallback)
            }
        val texts =
            warnings.zip(localized) { warning, translation ->
                translation
                    ?: WarningText(warning.headline, warning.description, warning.instruction)
            }
        val next = Display(warnings, texts, note, zone)
        if (next == displayed) return
        displayed = next

        val context = view.context
        body.removeAllViews()
        if (note != null) {
            body.addView(context.label(note, 12f, Hud.muted))
            body.space(12)
        }
        for ((warning, content) in warnings.zip(texts)) {
            body.addView(
                context.label(content.headline, 15f, Hud.amber).apply {
                    textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                }
            )
            body.space(6)
            val start = Times.format(warning.starts, "EEE, d MMM yyyy · HH:mm", zone)
            val end = Times.format(warning.expires, "EEE, d MMM yyyy · HH:mm", zone)
            body.addView(context.label("From $start\nUntil $end", 12f, Hud.muted))
            for (message in
                listOf(content.description, content.instruction).filter { it.isNotBlank() }) {
                body.space(8)
                body.addView(
                    context.label(message, 14f).apply {
                        textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                    }
                )
            }
            body.space(18)
        }
        val credit =
            listOf(context.getString(R.string.warning_source)) +
                warnings.map { it.geometryCredit }.filter { it.isNotBlank() }.distinct()
        body.addView(context.label(credit.joinToString("\n"), 10f, Hud.muted))
    }
}
