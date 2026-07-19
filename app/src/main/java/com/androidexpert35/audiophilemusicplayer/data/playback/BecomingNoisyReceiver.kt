package com.androidexpert35.audiophilemusicplayer.data.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Cold [Flow] that emits `Unit` whenever the Android framework broadcasts
 * [AudioManager.ACTION_AUDIO_BECOMING_NOISY] (wired headphone unplug, BT
 * headset disconnect, etc.).
 *
 * Wrapped in a [callbackFlow] so the [BroadcastReceiver] is unregistered
 * automatically when the collector's scope is cancelled — no manual lifecycle
 * wiring, no leaked receivers.
 *
 * Follows the project's mandatory `callbackFlow` convention for every
 * register/unregister-style Android API.
 *
 * @param context Application context used to register the broadcast receiver.
 * @return Cold [Flow] of becoming-noisy notifications.
 */
fun observeBecomingNoisy(context: Context): Flow<Unit> = callbackFlow {
    val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                trySend(Unit)
            }
        }
    }
    // RECEIVER_NOT_EXPORTED is safe here — the broadcast originates from the
    // framework itself; no third-party app should spoof it.
    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    awaitClose { runCatching { context.unregisterReceiver(receiver) } }
}

