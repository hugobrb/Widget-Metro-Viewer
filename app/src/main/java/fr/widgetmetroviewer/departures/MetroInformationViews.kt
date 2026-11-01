package fr.widgetmetroviewer.departures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Défilement local chez le lanceur : aucun réveil du processus ni appel API pour changer d'avis. */
internal object MetroInformationViews {
    private val badges = ConcurrentHashMap<String, Bitmap>()
    fun build(context: Context, line: String, panel: MetroPanelData, now: Instant = Instant.now()): RemoteViews =
        RemoteViews(context.packageName, R.layout.metro_panel_information).also { populate(context, it, line, panel, now) }

    fun populate(context: Context, views: RemoteViews, line: String, panel: MetroPanelData, now: Instant = Instant.now()) {
        val groups = panel.informationNotices.ifEmpty {
            listOf(MetroTrafficNotice(panel.information, panel.informationLines.ifEmpty { listOf(line) }))
        }
        val flipper = RemoteViews(context.packageName, if (groups.size > 1) R.layout.metro_information_flipper else R.layout.metro_information_static)
        groups.forEachIndexed { index, group ->
            val frame = RemoteViews(context.packageName, R.layout.metro_information_frame)
            val lines = group.lines.ifEmpty { listOf(line) }.distinctBy { MetroLineStyles.key(it) }.take(16)
            frame.removeAllViews(R.id.info_group_lines)
            val row = RemoteViews(context.packageName, R.layout.metro_information_badge_row)
            // Même cellule pour le M et les numéros ; les places libres restent à droite.
            row.setFloat(R.id.info_badge_row, "setWeightSum", maxOf(5, lines.size + 1).toFloat())
            lines.forEach { name ->
                val cell = RemoteViews(context.packageName, R.layout.metro_information_badge)
                cell.setImageViewBitmap(R.id.info_line_badge, badge(name))
                cell.setContentDescription(R.id.info_line_badge, "Ligne " + MetroLineStyles.label(name))
                row.addView(R.id.info_badge_row, cell)
            }
            frame.addView(R.id.info_group_lines, row)
            frame.setTextViewText(R.id.info_group_text, group.displayText)
            frame.setTextViewText(R.id.info_group_count, if (groups.size > 1) "Information " + (index + 1) + " / " + groups.size else "")
            flipper.addView(R.id.widget_info_flipper, frame)
        }
        flipper.setInt(R.id.widget_info_flipper, "setDisplayedChild", MetroInformationRotation.index(now, groups.size))
        views.removeAllViews(R.id.widget_info_host)
        views.addView(R.id.widget_info_host, flipper)
        views.setViewVisibility(R.id.widget_info_container, View.VISIBLE)
    }
    private fun badge(line: String): Bitmap = badges.getOrPut(MetroLineStyles.key(line)) {
        val style = MetroLineStyles.get(line)
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = style.background
            canvas.drawCircle(48f, 48f, 46f, paint)
            paint.color = style.foreground
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = if (MetroLineStyles.isBis(line)) 48f else 58f
            val number = MetroLineStyles.number(line)
            val baseline = 48f - (paint.ascent() + paint.descent()) / 2 - if (MetroLineStyles.isBis(line)) 8f else 0f
            canvas.drawText(number, 48f - paint.measureText(number) / 2, baseline, paint)
            if (MetroLineStyles.isBis(line)) {
                paint.textSize = 20f
                canvas.drawText("bis", 48f - paint.measureText("bis") / 2, 82f, paint)
            }
        }
    }
}
