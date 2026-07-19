package com.androidexpert35.audiophilemusicplayer.domain.model.track

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistIdentityTest {

    @Test
    fun `given scanner fallback artist when checked then identity is unknown`() {
        assertTrue("Unknown Artist".isUnknownArtistName())
    }

    @Test
    fun `given MediaStore sentinel artist when checked then identity is unknown`() {
        assertTrue(" <unknown> ".isUnknownArtistName())
    }

    @Test
    fun `given real artist when checked then identity is known`() {
        assertFalse("Michael Jackson".isUnknownArtistName())
    }
}
