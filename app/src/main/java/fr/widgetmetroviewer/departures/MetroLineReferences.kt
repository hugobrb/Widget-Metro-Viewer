package fr.widgetmetroviewer.departures

/** Identifiants commerciaux du catalogue officiel IDFM. */
internal object MetroLineReferences {
    private val references = mapOf(
        "4" to "STIF:Line::C01374:",
        "5" to "STIF:Line::C01375:",
        "6" to "STIF:Line::C01376:",
        "7" to "STIF:Line::C01377:",
        "8" to "STIF:Line::C01378:",
        "9" to "STIF:Line::C01379:",
        "10" to "STIF:Line::C01380:",
        "11" to "STIF:Line::C01381:",
        "12" to "STIF:Line::C01382:",
        "13" to "STIF:Line::C01383:",
        "14" to "STIF:Line::C01384:",
        "3B" to "STIF:Line::C01386:",
        "7B" to "STIF:Line::C01387:",
        "1" to "STIF:Line::C01371:",
        "2" to "STIF:Line::C01372:",
        "3" to "STIF:Line::C01373:"
    )
    fun forLine(line: String): String? = references[MetroLineStyles.key(line)]
}
