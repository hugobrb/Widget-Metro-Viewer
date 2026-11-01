package fr.widgetmetroviewer.departures

import java.util.Locale

internal data class MetroLineStyle(val background: Int, val foreground: Int)

/** Référentiel des lignes IDFM : ColourWeb_hexa et TextColourWeb_hexa (31/10/2026).
 * https://data.iledefrance-mobilites.fr/explore/dataset/referentiel-des-lignes/
 * Palette commune aux menus Compose et au dessin du widget.
 */
internal object MetroLineStyles {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val styles = mapOf(
        "1" to MetroLineStyle(0xFFFFBE00.toInt(), black),
        "2" to MetroLineStyle(0xFF0055C8.toInt(), white),
        "3" to MetroLineStyle(0xFF6E6E00.toInt(), white),
        "3B" to MetroLineStyle(0xFF82C8E6.toInt(), black),
        "4" to MetroLineStyle(0xFFA0006E.toInt(), white),
        "5" to MetroLineStyle(0xFFFF5A00.toInt(), black),
        "6" to MetroLineStyle(0xFF82DC73.toInt(), black),
        "7" to MetroLineStyle(0xFFFF82B4.toInt(), black),
        "7B" to MetroLineStyle(0xFF82DC73.toInt(), black),
        "8" to MetroLineStyle(0xFFD282BE.toInt(), black),
        "9" to MetroLineStyle(0xFFD2D200.toInt(), black),
        "10" to MetroLineStyle(0xFFDC9600.toInt(), black),
        "11" to MetroLineStyle(0xFF6E491E.toInt(), white),
        "12" to MetroLineStyle(0xFF00643C.toInt(), white),
        "13" to MetroLineStyle(0xFF82C8E6.toInt(), black),
        "14" to MetroLineStyle(0xFF640082.toInt(), white),
        "15" to MetroLineStyle(0xFFAAAAAA.toInt(), black),
        "18" to MetroLineStyle(0xFF00A092.toInt(), white)
    )

    fun key(line: String): String = line.trim().uppercase(Locale.ROOT)
        .replace(" ", "").replace("BIS", "B")
    fun get(line: String): MetroLineStyle = styles[key(line)]
        ?: MetroLineStyle(0xFF596575.toInt(), white)
    fun isBis(line: String): Boolean = key(line).endsWith("B")
    fun number(line: String): String = key(line).removeSuffix("B")
    fun label(line: String): String = number(line) + if (isBis(line)) " bis" else ""
}
