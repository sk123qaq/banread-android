package app.banread.library.internal

internal enum class MarkdownBlockKind(val persistedValue: String) {
    PARAGRAPH("paragraph"),
    HEADING("heading"),
    QUOTE("quote"),
    LIST("list"),
    TABLE("table"),
    IMAGE("image"),
    CODE("code"),
    FOOTNOTE("footnote"),
}
