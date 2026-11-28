package app.banread.app.ui.reader

enum class ReaderSheet {
    NONE,
    TYPOGRAPHY,
    THEME,
    MORE,
    TOC,
}

fun ReaderSheet.panelHeightFraction(): Float? = when (this) {
    ReaderSheet.TYPOGRAPHY -> 0.4f
    ReaderSheet.THEME -> 0.44f
    ReaderSheet.MORE -> 0.52f
    ReaderSheet.NONE, ReaderSheet.TOC -> null
}
