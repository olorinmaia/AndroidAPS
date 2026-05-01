package app.aaps.auto.data

data class AutoData(
    val bgText: String,
    val bgValid: Boolean,
    val deltaText: String,
    val trendSymbol: String,
    val timeAgoText: String,
    val iobText: String,
    val cobText: String,
    val basalDisplayText: String,
    val loopModeText: String,
    val profileText: String,
    val tempTargetText: String,
    val tempTargetActive: Boolean
)
