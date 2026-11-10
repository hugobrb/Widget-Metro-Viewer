package fr.widgetmetroviewer.departures

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Un seul dessin pour l'aperçu et RemoteViews. Coordonnées de la référence : 1851 × 849. */
internal object MetroPanelRenderer {
    const val ASPECT_RATIO = 1851f / 849f
    private const val WIDTH = 1851f
    private const val HEIGHT = 849f
    private val ink = Color.rgb(10, 19, 29)
    private val yellow = Color.rgb(255, 215, 0)
    private val green = Color.rgb(73, 158, 94)
    private val grey = Color.rgb(193, 193, 193)
    fun description(station: String, line: String, panel: MetroPanelData): String =
        MetroPanelPresentation.description(station, line, panel)

    fun bitmap(station: String, line: String, panel: MetroPanelData): Bitmap {
        // RGB_565 : environ 0,92 Mo, pour limiter le poids transmis au lanceur.
        val bitmap = Bitmap.createBitmap(1000, 459, Bitmap.Config.RGB_565)
        draw(Canvas(bitmap), 1000f, 459f, station, line, panel)
        return bitmap
    }

    fun draw(canvas: Canvas, width: Float, height: Float, station: String, line: String,
             panel: MetroPanelData) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val lineStyle = MetroLineStyles.get(line)
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.drawColor(Color.BLACK)
        // Remplir le panneau sans ajouter de bandes autour du dessin.
        canvas.scale(width / WIDTH, height / HEIGHT)
        fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawRect(x, y, x + w, y + h, paint)
        }
        fun text(value: String, x: Float, baseline: Float, size: Float,
                 color: Int, bold: Boolean = false, maxWidth: Float = Float.MAX_VALUE) {
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            paint.textSize = size
            val measured = paint.measureText(value)
            if (measured > maxWidth) paint.textSize = size * maxWidth / measured
            canvas.drawText(value, x, baseline, paint)
        }
        fun circle(x: Float, y: Float, radius: Float, color: Int) {
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius, paint)
        }
        fun metro(cx: Float, cy: Float, radius: Float) {
            paint.color = ink
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * .11f
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.FILL
            text("M", cx - radius * .66f, cy + radius * .55f, radius * 1.55f, ink)
        }
        fun badge(cx: Float, cy: Float, radius: Float) {
            circle(cx, cy, radius, lineStyle.background)
            val bis = MetroLineStyles.isBis(line)
            val number = MetroLineStyles.number(line)
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = radius * if (bis) 1.12f else 1.35f
            // Centrage selon les métriques réelles, pour les numéros à un ou deux chiffres.
            val numberWidth = paint.measureText(number)
            val baseline = cy - (paint.ascent() + paint.descent()) / 2 - if (bis) radius * .16f else 0f
            text(number, cx - numberWidth / 2, baseline, paint.textSize,
                lineStyle.foreground, true, radius * 1.65f)
            if (bis) {
                paint.textSize = radius * .43f
                val bisWidth = paint.measureText("bis")
                text("bis", cx - bisWidth / 2, cy + radius * .64f,
                    radius * .43f, lineStyle.foreground, true)
            }
        }

        // En-tête blanc, logos et nom de station.
        rect(0f, 0f, WIDTH, 228f, Color.WHITE)
        metro(134f, 114f, 86f)
        badge(333f, 114f, 91f)
        text(station, 466f, 157f, 128f, Color.rgb(6, 8, 51), true, 1100f)
        fun departure(column: MetroPanelColumn, x: Float, width: Float) {
            text("Direction", x, 310f, 40f, grey)
            if (column.destinations.size > 1) {
                column.destinations.take(2).forEachIndexed { index, destination ->
                    text(destination, x, 352f + index * 43f, 38f, Color.WHITE, maxWidth = width)
                }
            } else text(column.label, x, 362f, 46f, Color.WHITE, maxWidth = width)
            if (column.value != null) {
                if (column.unit == "min") {
                    text(column.value, x - 4f, 575f, 226f, yellow, true, width - 120f)
                    val unitX = x - 4f + paint.measureText(column.value) + 16f
                    text("min", unitX, 568f, 60f, yellow, true)
                } else text(column.value, x - 4f, 557f, 180f, yellow, true, width)
            } else text("—", x, 550f, 150f, grey, true)
            text(column.detail, x, 607f, 28f, grey, maxWidth = width)
            text(column.following, x, 654f, 38f, grey, maxWidth = width)
            text(column.followingDestination, x, 702f, 29f, grey, maxWidth = width)
        }
        departure(panel.first, 49f, 425f)
        rect(499f, 306f, 4f, 396f, Color.WHITE)
        departure(panel.second, 582f, 370f)

        // Informations de récupération et de disponibilité ; aucun incident inventé en mode réel.
        rect(990f, 257f, 833f, 467f, Color.rgb(250, 250, 250))
        metro(1061f, 332f, 45f)
        badge(1169f, 332f, 47f)
        paint.color = Color.rgb(249, 191, 0)
        canvas.drawRoundRect(RectF(1235f,306f,1296f,366f), 5f, 5f, paint)
        text(if (panel.demo || panel.trafficAlert == true) "!" else "i", 1252f, 355f, 56f, Color.WHITE, true)
        paint.color = Color.BLACK
        canvas.drawRoundRect(RectF(1601f,266f,1800f,353f), 5f, 5f, paint)
        // L’heure est superposée par metro_panel_clock.xml (TextClock natif).
        // Retour à la ligne avec ellipse, en gardant une taille lisible pour l'encart.
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 47f
        val lines = mutableListOf<String>()
        var current = ""
        for (word in panel.information.split(Regex("\\s+"))) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= 780f || current.isEmpty()) current = candidate
            else { lines += current; current = word }
        }
        if (current.isNotEmpty()) lines += current
        lines.take(5).forEachIndexed { index, value ->
            val displayed = if (index == 4 && lines.size > 5) value.dropLast(3) + "…" else value
            text(displayed, 1018f, 442f + index * 56f, 47f, Color.BLACK, maxWidth = 780f)
        }
        rect(0f, 750f, WIDTH, 99f, Color.rgb(207, 199, 0))
        circle(70f, 797f, 18f, if (panel.demo || panel.trafficAlert == false) green else if (panel.trafficAlert == true) Color.rgb(218, 87, 0) else grey)
        text(panel.footer, 110f, 811f, 38f, Color.BLACK, true, 1050f)
        text(panel.footerTag, 1230f, 804f, 28f, Color.BLACK, true, 570f)

        canvas.restore()
    }
}
