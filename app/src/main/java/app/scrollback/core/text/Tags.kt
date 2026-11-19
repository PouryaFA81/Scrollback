package app.scrollback.core.text

/** Helpers for user-entered tags ("#funny, cooking  recipes" -> [funny, cooking, recipes]). */
object Tags {
    fun parse(input: String): List<String> =
        input.split(',', ' ', '\n', '\t')
            .map { it.trim().trimStart('#').lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun format(tags: List<String>): String = tags.joinToString(" ") { "#$it" }
}
