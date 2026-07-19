package com.androidexpert35.audiophilemusicplayer.presentation.error

import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudiophileUiErrorMapperTest {

    private val mapper = AudiophileUiErrorMapper(TestStringResolver)

    @Test
    fun `given playback failure when mapped then playback copy and payload are preserved`() {
        val failure = PlaybackResourceError(
            message = "Decoder rejected the stream",
            errorCode = 4003
        )

        val result = mapper.mapResourceError(failure)

        assertEquals(R.string.error_playback_title.toString(), result.title)
        assertEquals(failure.message, result.message)
        assertSame(failure, result.type)
    }
}
