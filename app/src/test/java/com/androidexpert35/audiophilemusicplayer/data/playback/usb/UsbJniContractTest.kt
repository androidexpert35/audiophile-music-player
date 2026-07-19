package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the USB Kotlin JNI declarations against missing native entry points. */
class UsbJniContractTest {

    @Test
    fun `submit failure is rejected while tagged negative pointers remain valid`() {
        assertFalse(EngineSwapBridge.isValidBridgeHandle(-6L))
        assertTrue(EngineSwapBridge.isValidBridgeHandle(Long.MIN_VALUE))
    }

    @Test
    fun `every usb external declaration has a matching cpp jni symbol`() {
        val appRoot = resolveAppRoot()
        val cppText = File(appRoot, "src/main/cpp")
            .walkTopDown()
            .filter { it.isFile && it.extension == "cpp" }
            .joinToString(separator = "\n") { it.readText() }

        val missingSymbols = BRIDGE_CLASSES.flatMap { bridgeClass ->
            val kotlinFile = File(
                appRoot,
                "src/main/java/com/androidexpert35/audiophilemusicplayer/" +
                    "data/playback/usb/$bridgeClass.kt",
            )
            EXTERNAL_FUNCTION.findAll(kotlinFile.readText()).mapNotNull { match ->
                val functionName = match.groupValues[1]
                val symbol = JNI_PREFIX + bridgeClass + "_" + functionName
                if (cppText.contains(symbol)) null else symbol
            }
        }

        assertTrue(
            "Missing native USB JNI symbols: ${missingSymbols.joinToString()}",
            missingSymbols.isEmpty(),
        )
    }

    private fun resolveAppRoot(): File {
        val workingDirectory = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(workingDirectory, File(workingDirectory, "app"))
            .firstOrNull { File(it, "src/main/cpp").isDirectory }
            ?: error("Cannot locate app/src/main/cpp from $workingDirectory")
    }

    private companion object {
        val BRIDGE_CLASSES = listOf("EngineSwapBridge", "UsbAudioBridge")
        val EXTERNAL_FUNCTION = Regex("external\\s+fun\\s+(\\w+)")
        const val JNI_PREFIX =
            "Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_"
    }
}
