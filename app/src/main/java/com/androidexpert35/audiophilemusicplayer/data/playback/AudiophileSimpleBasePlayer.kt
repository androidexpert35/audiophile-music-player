package com.androidexpert35.audiophilemusicplayer.data.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioPlayerEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/**
 * Media3 adapter that exposes the active [AudioPlayerEngine] strategy (via
 * [AudioEngineManager]) as a [SimpleBasePlayer] so the existing
 * `MediaSessionService` + `MediaController` + system-notification
 * infrastructure continues to work unchanged across engine hot-swaps.
 *
 * The adapter holds the playlist (a list of [MediaItem]) as first-class state
 * and mirrors the current track's URI into the engine. Every control command
 * funnels through the `handle*` overrides, which post work to the engine's
 * audio thread and return immediately — `invalidateState()` is driven from
 * the engine's [AudioPlayerEngine.Listener] callback so Media3's state
 * snapshot stays in sync with whichever strategy is active.
 *
 * ### Gapless preload
 *
 * Whenever the playlist changes or the active index shifts, the adapter
 * informs the engine about the *next* item via
 * [AudioPlayerEngine.enqueueNext] so it can preload a format-compatible
 * follower and perform a sink-preserving swap on EOS (Audiophile engine)
 * or a seamless ExoPlayer transition (Standard engine).
 */
@OptIn(UnstableApi::class)
@Singleton
class AudiophileSimpleBasePlayer @Inject constructor(
    private val engine: AudioEngineManager,
) : SimpleBasePlayer(Looper.getMainLooper()) {


    /**
     * Handler bound to the application (main) looper. [SimpleBasePlayer]
     * requires every `invalidateState()` call to happen on this thread, but
     * the engine's [AudioPlayerEngine.Listener] may fire on its own
     * audio thread — so we trampoline the invalidation through this handler.
     */
    private val appHandler = Handler(Looper.getMainLooper())

    /**
     * One queue slot: the [mediaItem] plus the stable [uid] Media3 uses to track
     * the slot across state snapshots.
     *
     * The UID must be unique **per queue entry, not per track**:
     * `SimpleBasePlayer.State.Builder.setPlaylist` rejects duplicate UIDs with an
     * `IllegalArgumentException`, so a queue holding the same song twice (repeated
     * "add to queue", or a playlist listing one file twice) would crash every
     * `invalidateState()` if the UID were derived from `mediaId` alone. The UID is
     * assigned once, when the entry joins the queue, and never regenerated —
     * Media3 relies on UID stability to correlate items across snapshots, so a
     * position-derived UID would break transitions whenever the queue is
     * reordered.
     */
    private data class QueueEntry(val uid: String, val mediaItem: MediaItem)

    /** Monotonic source of per-entry UID suffixes; only touched on the main thread. */
    private var queueUidSequence: Long = 0L

    private var playlist: MutableList<QueueEntry> = mutableListOf()

    /**
     * Queue in its user-selected order, retained while [playlist] is shuffled.
     *
     * Keeping this separately lets turning shuffle off restore the original queue
     * without interrupting the current item.
     */
    private var originalPlaylist: List<QueueEntry> = emptyList()

    private var currentMediaItemIndex: Int = 0
    private var playWhenReady: Boolean = false
    private var repeatMode: Int = REPEAT_MODE_OFF
    private var shuffleModeEnabled: Boolean = false
    private var pendingSeekPositionMs: Long = NO_PENDING_SEEK_POSITION_MS
    private var pendingSeekMediaItemIndex: Int = INDEX_UNSET

    /** Index currently preloaded in the engine for the next automatic transition. */
    private var preloadedNextMediaItemIndex: Int? = null

    /**
     * The start position passed to the most recent [loadCurrent] call.
     *
     * Captured before delegating to the engine so that, if the engine later
     * resets to [EnginePlaybackState.IDLE] from an external `stop()` command
     * (e.g. during a mid-session service restart or an aggressive OEM background
     * kill), a subsequent [handleSetPlayWhenReady] can reload from this offset
     * instead of restarting the track from the beginning.
     */
    private var lastLoadedPositionMs: Long = 0L

    init {
        engine.setListener(object : AudioPlayerEngine.Listener {
            override fun onEngineStateChanged() {
                runOnApplicationThread {
                    // When the engine stops on its own (end of queue, or an
                    // unrecoverable load/write error) the user's play intent is
                    // over: mirror that into the adapter's playWhenReady.
                    // Leaving it true made MediaController report a phantom
                    // "playing" intent, which turned the app's resume() into a
                    // no-op after every background auto-advance failure.
                    val engineState = engine.state.value
                    if (engineState == EnginePlaybackState.PAUSED ||
                        engineState == EnginePlaybackState.ENDED ||
                        engineState == EnginePlaybackState.ERROR
                    ) {
                        playWhenReady = false
                    }
                    clearPendingSeekIfSettled()
                    invalidateState()
                }
            }

            override fun onTrackAdvanced() {
                // The engine has moved to the item selected by scheduleNextTrackPreload
                // (gapless or format-mismatch auto-advance). Resolve that preloaded
                // target rather than blindly incrementing, so shuffle and both repeat
                // modes remain correct on the audiophile and standard engines alike.
                runOnApplicationThread {
                    clearPendingSeek()
                    advanceMediaItemIndex()
                }
            }
        })
    }

    /**
     * Executes [block] on the application (main) thread. If the caller is already
     * on the main thread the block runs inline; otherwise it is posted via [appHandler].
     *
     * Centralises the "hop to main thread if needed" pattern required by
     * [SimpleBasePlayer], which enforces single-threaded state access on its
     * construction looper.
     */
    private inline fun runOnApplicationThread(crossinline block: () -> Unit) {
        appHandler.runOrPost(block)
    }

    // ── State snapshot ───────────────────────────────────────────────────────

    override fun getState(): State {
        val mediaItemData = playlist.map { entry ->
            MediaItemData.Builder(entry.uid)
                .setMediaItem(entry.mediaItem)
                .setDurationUs(durationUsFor(entry.mediaItem))
                .build()
        }

        val enginePlayback = engine.state.value
        val resolvedContentPositionMs = resolveContentPositionMs()
        val mediaState = when {
            playlist.isEmpty() -> STATE_IDLE
            enginePlayback == EnginePlaybackState.LOADING -> STATE_BUFFERING
            enginePlayback == EnginePlaybackState.ENDED -> STATE_ENDED
            enginePlayback == EnginePlaybackState.IDLE -> STATE_IDLE
            enginePlayback == EnginePlaybackState.ERROR -> STATE_IDLE
            else -> STATE_READY
        }

        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_DEFAULT_POSITION,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_GET_TRACKS,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_RELEASE,
            )
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(mediaItemData)
            .setCurrentMediaItemIndex(currentMediaItemIndex.coerceIn(0, max(0, playlist.lastIndex)))
            .setContentPositionMs { resolvedContentPositionMs }
            .setContentBufferedPositionMs { resolvedContentPositionMs }
            .setPlaybackState(mediaState)
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleModeEnabled)
            .apply {
                // Surface engine failures to Media3 so the UI / notification / logcat
                // see a concrete error instead of a silent STATE_IDLE. The most common
                // cause in development is FFmpeg not being provisioned — the message
                // from BitPerfectPlaybackEngine.lastErrorMessage says exactly that.
                if (enginePlayback == EnginePlaybackState.ERROR) {
                    val msg = engine.lastErrorMessage
                        ?: "Unknown playback engine failure"
                    setPlayerError(
                        PlaybackException(
                            msg,
                            /* cause = */ null,
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                        )
                    )
                }
            }
            .build()
    }

    private fun durationUsFor(item: MediaItem): Long {
        val durationMs = item.mediaMetadata.durationMs ?: return C_TIME_UNSET
        return if (durationMs > 0L) durationMs * 1_000L else C_TIME_UNSET
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    /**
     * Wraps [item] in a [QueueEntry] with a freshly assigned, entry-unique UID.
     *
     * The human-readable identity (mediaId, or the URI when the id is empty) is
     * kept as the UID prefix purely for log/debug readability; uniqueness comes
     * from the monotonic sequence suffix.
     */
    private fun toQueueEntry(item: MediaItem): QueueEntry {
        val identity = item.mediaId.ifEmpty { item.localConfiguration?.uri?.toString().orEmpty() }
        return QueueEntry(uid = "$identity#${queueUidSequence++}", mediaItem = item)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        originalPlaylist = mediaItems.map(::toQueueEntry)
        playlist = originalPlaylist.toMutableList()
        currentMediaItemIndex = startIndex.coerceIn(0, max(0, playlist.lastIndex))
        if (shuffleModeEnabled) shuffleUpcomingItems()
        clearPendingSeek()
        val startPos = if (startPositionMs == C_TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0L)
        loadCurrent(startPos, autoPlay = playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val insertionIndex = index.coerceIn(0, playlist.size)
        if (playlist.isNotEmpty() && insertionIndex <= currentMediaItemIndex) {
            currentMediaItemIndex += mediaItems.size
        }
        playlist.addAll(insertionIndex, mediaItems.map(::toQueueEntry))
        // An explicit queue mutation becomes the new listener-selected order. This
        // keeps play-next deterministic even when the previous queue was shuffled.
        originalPlaylist = playlist.toList()
        scheduleNextTrackPreload()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int
    ): ListenableFuture<*> {
        val indexedPlaylist = playlist.mapIndexed(::IndexedValue)
        val reordered = PlaybackQueueMutationResolver.moveRange(
            items = indexedPlaylist,
            fromIndex = fromIndex,
            toIndex = toIndex,
            newIndex = newIndex,
        )
        currentMediaItemIndex = reordered.indexOfFirst { it.index == currentMediaItemIndex }
            .takeIf { it >= 0 }
            ?: currentMediaItemIndex
        playlist = reordered.mapTo(mutableListOf()) { it.value }
        originalPlaylist = playlist.toList()
        scheduleNextTrackPreload()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (fromIndex in 0..playlist.size && toIndex in fromIndex..playlist.size) {
            val removedItemCount = toIndex - fromIndex
            playlist.subList(fromIndex, toIndex).clear()
            originalPlaylist = playlist.toList()
            clearPendingSeek()
            currentMediaItemIndex = when {
                currentMediaItemIndex >= toIndex -> currentMediaItemIndex - removedItemCount
                currentMediaItemIndex >= fromIndex -> fromIndex.coerceAtMost(max(0, playlist.lastIndex))
                else -> currentMediaItemIndex
            }
            scheduleNextTrackPreload()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        // Engine auto-loads on setMediaItems; prepare is a no-op sync point.
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            when (engine.state.value) {
                EnginePlaybackState.IDLE,
                EnginePlaybackState.ERROR -> if (playlist.isNotEmpty()) {
                    // The engine fell back to IDLE (e.g. an external stop() during a service
                    // restart, a BT stack flush, or an OEM background kill) or parked itself
                    // in ERROR (failed load / unrecoverable sink write). In both cases
                    // currentDecoder is null, so engine.play() would silently drop the
                    // command ("Case A"). Reload the current track from the last saved
                    // position instead and re-arm the full pipeline with autoPlay=true.
                    loadCurrent(startPositionMs = lastLoadedPositionMs, autoPlay = true)
                }

                EnginePlaybackState.ENDED -> if (playlist.isNotEmpty()) {
                    // The queue (or a stalled auto-advance) ended. The decoder sits at
                    // EOF, so engine.play() would immediately re-end; restart the
                    // current item from the top — the Media3 convention for a play
                    // command issued against an ENDED player.
                    loadCurrent(startPositionMs = 0L, autoPlay = true)
                }

                else -> {
                    engine.play()
                }
            }
        } else {
            engine.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (playlist.isEmpty()) return Futures.immediateVoidFuture()
        val target = mediaItemIndex.coerceIn(0, playlist.lastIndex)
        val startPos = if (positionMs == C_TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        pendingSeekMediaItemIndex = target
        pendingSeekPositionMs = startPos
        if (target != currentMediaItemIndex) {
            currentMediaItemIndex = target
            loadCurrent(startPos, autoPlay = playWhenReady)
        } else {
            engine.seekTo(startPos)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        clearPendingSeek()
        preloadedNextMediaItemIndex = null
        engine.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        clearPendingSeek()
        preloadedNextMediaItemIndex = null
        engine.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        scheduleNextTrackPreload()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (this.shuffleModeEnabled == shuffleModeEnabled) return Futures.immediateVoidFuture()
        this.shuffleModeEnabled = shuffleModeEnabled
        if (shuffleModeEnabled) {
            shuffleUpcomingItems()
        } else {
            restoreOriginalQueueOrder()
        }
        scheduleNextTrackPreload()
        return Futures.immediateVoidFuture()
    }

    /**
     * Applies the preloaded transition target reported by the engine.
     *
     * The target can be the current item for repeat-one, the first item for
     * repeat-all, or the next item in the active shuffled playback order.
     */
    private fun advanceMediaItemIndex() {
        if (playlist.isEmpty()) return
        clearPendingSeek()
        val nextIndex = preloadedNextMediaItemIndex ?: nextMediaItemIndex()
        preloadedNextMediaItemIndex = null
        if (nextIndex != null) {
            currentMediaItemIndex = nextIndex
            // The engine started the advanced-to track from its beginning, so any
            // future ERROR/IDLE recovery reload must target offset 0 — not the
            // offset of the last manual load (which belonged to a previous track).
            lastLoadedPositionMs = 0L
            // Preload the item after the one we just advanced to, so gapless
            // continues to function correctly for subsequent auto-advances.
            scheduleNextTrackPreload()
        }
    }

    private fun loadCurrent(startPositionMs: Long, autoPlay: Boolean) {
        val item = playlist.getOrNull(currentMediaItemIndex)?.mediaItem ?: run {
            clearPendingSeek()
            engine.stop()
            return
        }
        val uri = item.localConfiguration?.uri?.toString() ?: return
        // Capture the load offset so handleSetPlayWhenReady can reload from
        // this position if the engine unexpectedly resets to IDLE later.
        lastLoadedPositionMs = startPositionMs
        engine.load(uri, startPositionMs, autoPlay = autoPlay)
        scheduleNextTrackPreload()
    }

    /**
     * Whether a seek is pending for the currently displayed media item.
     *
     * Both [resolveContentPositionMs] and [clearPendingSeekIfSettled] share
     * this guard — extracting it avoids repeating the two-condition expression
     * and gives the concept a meaningful name.
     */
    private val hasPendingSeekForCurrentItem: Boolean
        get() = pendingSeekPositionMs != NO_PENDING_SEEK_POSITION_MS &&
            pendingSeekMediaItemIndex == currentMediaItemIndex

    /**
     * Returns the position that should be exposed to Media3 controllers.
     *
     * During FFmpeg seeks the engine updates its mirrored position flow
     * asynchronously on the audio thread. Until that happens, controllers can
     * briefly observe `0` if they query immediately after [handleSeek] returns.
     * This method keeps the target seek position visible until the engine
     * catches up.
     */
    private fun resolveContentPositionMs(): Long {
        val enginePositionMs = engine.positionMs.value.coerceAtLeast(0L)
        if (!hasPendingSeekForCurrentItem) return enginePositionMs
        return if (abs(enginePositionMs - pendingSeekPositionMs) <= SEEK_SETTLE_TOLERANCE_MS) {
            enginePositionMs
        } else {
            pendingSeekPositionMs
        }
    }

    /** Clears the optimistic seek once the engine-reported playhead reaches it. */
    private fun clearPendingSeekIfSettled() {
        if (!hasPendingSeekForCurrentItem) return
        if (abs(engine.positionMs.value.coerceAtLeast(0L) - pendingSeekPositionMs) <= SEEK_SETTLE_TOLERANCE_MS) {
            clearPendingSeek()
        }
    }

    /** Resets any optimistic seek retained for the current media item. */
    private fun clearPendingSeek() {
        pendingSeekPositionMs = NO_PENDING_SEEK_POSITION_MS
        pendingSeekMediaItemIndex = INDEX_UNSET
    }

    private fun scheduleNextTrackPreload() {
        val nextIndex = nextMediaItemIndex()
        preloadedNextMediaItemIndex = nextIndex
        val nextUri = playlist
            .getOrNull(nextIndex ?: INDEX_UNSET)
            ?.mediaItem
            ?.localConfiguration
            ?.uri
            ?.toString()
        engine.enqueueNext(nextUri)
    }

    /** Resolves the effective automatic follower from the current queue modes. */
    private fun nextMediaItemIndex(): Int? =
        PlaybackQueueOrderResolver.nextIndex(
            queueSize = playlist.size,
            currentIndex = currentMediaItemIndex,
            repeatMode = repeatMode,
        )

    /**
     * Randomises only the as-yet-unplayed part of the queue.
     *
     * The current item and its history retain their positions so enabling shuffle
     * never restarts playback or makes the previous button lose its immediate
     * context. Every later item is played exactly once before repeat-all starts
     * the same shuffled cycle again.
     */
    private fun shuffleUpcomingItems() {
        playlist = PlaybackQueueOrderResolver.shuffleUpcoming(
            playlist = playlist,
            originalPlaylist = originalPlaylist,
            currentIndex = currentMediaItemIndex,
            uidOf = QueueEntry::uid,
        ).toMutableList()
    }

    /** Restores [originalPlaylist] while preserving the currently playing item. */
    private fun restoreOriginalQueueOrder() {
        val currentUid = playlist.getOrNull(currentMediaItemIndex)?.uid
        playlist = originalPlaylist.toMutableList()
        currentMediaItemIndex = playlist.indexOfFirst { it.uid == currentUid }
            .takeIf { it >= 0 }
            ?: currentMediaItemIndex.coerceIn(0, max(0, playlist.lastIndex))
    }

    private companion object {
        // androidx.media3.common.C.TIME_UNSET is Long.MIN_VALUE + 1; inlined to avoid
        // pulling yet another C.* reference into the public surface of this file.
        const val C_TIME_UNSET: Long = Long.MIN_VALUE + 1
        const val INDEX_UNSET = -1
        const val NO_PENDING_SEEK_POSITION_MS = Long.MIN_VALUE
        const val SEEK_SETTLE_TOLERANCE_MS = 750L
    }
}
