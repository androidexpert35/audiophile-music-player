package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase
import com.androidexpert35.audiophilemusicplayer.data.mapper.mergeStationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.data.mapper.toDomain
import com.androidexpert35.audiophilemusicplayer.data.mapper.toStationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Verifies the production Class S bridge and cache contract on an Android device. */
@RunWith(AndroidJUnit4::class)
class AudioAnalysisBridgeDeviceTest {

    /** Confirms packed stereo samples reach aggregation and survive Room persistence. */
    @Test
    fun `packed float sink produces and persists finite stereo measurements`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = stereoFixture(context.cacheDir)
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AudiophileDatabase::class.java,
        ).build()

        try {
            val sampled = FFmpegStationarySampler().sample(fixture.absolutePath)
            val features = (sampled as? StationarySamplingResult.Measured)?.features
            checkNotNull(features) { "Stereo WAV fixture was not measured: $sampled" }
            assertTrue(features.midRmsDbfs?.isFinite() == true)
            assertTrue(features.sideRmsDbfs?.isFinite() == true)
            assertTrue(features.interChannelCorrelation?.isFinite() == true)

            val row = mergeStationaryAnalysis(
                existing = null,
                audioKey = FIXTURE_AUDIO_KEY,
                stationary = features.toStationaryAnalysis(),
                schemaVersion = TrackAnalysis.SCHEMA_VERSION,
                nowEpochSeconds = FIXTURE_TIMESTAMP_SECONDS,
            )
            database.trackAnalysisDao().upsert(row)

            val persisted = database.trackAnalysisDao()
                .getByAudioKey(FIXTURE_AUDIO_KEY)
                ?.toDomain(TrackAnalysis.SCHEMA_VERSION)
                ?.stationary
            assertTrue(persisted?.midRmsDbfs?.isFinite() == true)
            assertTrue(persisted?.sideRmsDbfs?.isFinite() == true)
            assertTrue(persisted?.interChannelCorrelation?.isFinite() == true)
        } finally {
            database.close()
            fixture.delete()
        }
    }

    /** @return PCM WAV fixture with distinct stereo-channel phase and level. */
    private fun stereoFixture(directory: File): File {
        val dataSize = FRAME_COUNT * CHANNEL_COUNT * Short.SIZE_BYTES
        val wave = ByteBuffer.allocate(WAV_HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray(Charsets.US_ASCII))
        wave.putInt(WAV_HEADER_BYTES - 8 + dataSize)
        wave.put("WAVE".toByteArray(Charsets.US_ASCII))
        wave.put("fmt ".toByteArray(Charsets.US_ASCII))
        wave.putInt(PCM_FORMAT_CHUNK_BYTES)
        wave.putShort(PCM_FORMAT_CODE)
        wave.putShort(CHANNEL_COUNT.toShort())
        wave.putInt(SAMPLE_RATE_HZ)
        wave.putInt(SAMPLE_RATE_HZ * CHANNEL_COUNT * Short.SIZE_BYTES)
        wave.putShort((CHANNEL_COUNT * Short.SIZE_BYTES).toShort())
        wave.putShort(PCM_BITS_PER_SAMPLE)
        wave.put("data".toByteArray(Charsets.US_ASCII))
        wave.putInt(dataSize)
        repeat(FRAME_COUNT) { frame ->
            val phase = 2.0 * PI * FIXTURE_FREQUENCY_HZ * frame / SAMPLE_RATE_HZ
            wave.putShort((LEFT_AMPLITUDE * sin(phase) * Short.MAX_VALUE).toInt().toShort())
            wave.putShort((RIGHT_AMPLITUDE * cos(phase) * Short.MAX_VALUE).toInt().toShort())
        }
        return File.createTempFile("aud14-stereo-", ".wav", directory).apply {
            outputStream().use { it.write(wave.array()) }
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNEL_COUNT = 2
        const val FRAME_COUNT = SAMPLE_RATE_HZ * 4
        const val WAV_HEADER_BYTES = 44
        const val PCM_FORMAT_CHUNK_BYTES = 16
        const val PCM_FORMAT_CODE: Short = 1
        const val PCM_BITS_PER_SAMPLE: Short = 16
        const val FIXTURE_FREQUENCY_HZ = 997.0
        const val LEFT_AMPLITUDE = 0.5
        const val RIGHT_AMPLITUDE = 0.35
        const val FIXTURE_AUDIO_KEY = "aud14-device-stereo-fixture"
        const val FIXTURE_TIMESTAMP_SECONDS = 1L
    }
}
