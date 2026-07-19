package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

/**
 * Structured representation of DSP info dialog copy.
 */
internal sealed interface DspInfoBodyBlock {
    /**
     * Regular paragraph content rendered as body text.
     *
     * @property text Human-readable paragraph copy.
     */
    data class Paragraph(val text: String) : DspInfoBodyBlock

    /**
     * Bulleted list content rendered as multiple list rows.
     *
     * @property items Bullet item copy in display order.
     */
    data class BulletList(val items: List<String>) : DspInfoBodyBlock
}

/**
 * Parses multiline DSP copy into paragraphs and bullet lists.
 *
 * The Android resources system can flatten unescaped whitespace, so callers are
 * expected to provide explicit `\n` separators in XML for list-oriented copy.
 */
internal object DspInfoBodyParser {

    private const val BulletMarker = '•'

    /**
     * Converts the raw dialog body into renderable blocks.
     *
     * @param rawText String resource content resolved for the current locale.
     * @return Ordered blocks preserving paragraph and bullet-list grouping.
     */
    fun parse(rawText: String): List<DspInfoBodyBlock> {
        val normalizedText = rawText
            .replace("\r\n", "\n")
            .trim()

        if (normalizedText.isEmpty()) {
            return emptyList()
        }

        val blocks = mutableListOf<DspInfoBodyBlock>()
        val paragraphLines = mutableListOf<String>()
        val bulletItems = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphLines.isEmpty()) return

            blocks += DspInfoBodyBlock.Paragraph(
                text = paragraphLines.joinToString(separator = " ").trim(),
            )
            paragraphLines.clear()
        }

        fun flushBulletList() {
            if (bulletItems.isEmpty()) return

            blocks += DspInfoBodyBlock.BulletList(items = bulletItems.toList())
            bulletItems.clear()
        }

        normalizedText.lines().forEach { rawLine ->
            val line = rawLine.trim()

            when {
                line.isBlank() -> {
                    flushParagraph()
                    flushBulletList()
                }

                line.startsWith(BulletMarker) -> {
                    flushParagraph()
                    val bulletText = line.removePrefix(BulletMarker.toString()).trim()
                    if (bulletText.isNotEmpty()) {
                        bulletItems += bulletText
                    }
                }

                else -> {
                    flushBulletList()
                    paragraphLines += line
                }
            }
        }

        flushParagraph()
        flushBulletList()

        return blocks
    }
}
