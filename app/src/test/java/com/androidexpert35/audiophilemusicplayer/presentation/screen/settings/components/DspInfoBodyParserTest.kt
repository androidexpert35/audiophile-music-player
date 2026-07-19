package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DspInfoBodyParserTest {

    @Test
    fun `given paragraph bullet list and footer when parsing then blocks preserve the intended structure`() {
        val blocks = DspInfoBodyParser.parse(
            "Intro copy explaining the DSP.\n\n• First bullet\n• Second bullet\n• Third bullet\n\nClosing paragraph.",
        )

        assertEquals(
            listOf(
                DspInfoBodyBlock.Paragraph("Intro copy explaining the DSP."),
                DspInfoBodyBlock.BulletList(
                    items = listOf("First bullet", "Second bullet", "Third bullet"),
                ),
                DspInfoBodyBlock.Paragraph("Closing paragraph."),
            ),
            blocks,
        )
    }

    @Test
    fun `given windows line endings when parsing then line normalization still yields a bullet list`() {
        val blocks = DspInfoBodyParser.parse(
            "Lead paragraph.\r\n\r\n• Item one\r\n• Item two",
        )

        assertEquals(
            listOf(
                DspInfoBodyBlock.Paragraph("Lead paragraph."),
                DspInfoBodyBlock.BulletList(items = listOf("Item one", "Item two")),
            ),
            blocks,
        )
    }
}
