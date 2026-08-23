package com.androidexpert35.audiophilemusicplayer.data.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackController.Companion.POSITION_UPDATE_INTERVAL_MS
import com.androidexpert35.audiophilemusicplayer.data.playback.service.AudioPlaybackService
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges the [MediaController] ↔ domain repository layer.
 *
 * Manages the connection to [com.androidexpert35.audiophilemusicplayer.data.playback.service.AudioPlaybackService] via [SessionToken],
 * translates domain commands into Media3 player calls, and converts
 * Media3 state callbacks into domain [PlaybackState] / [QueueState] flows.
 *
 * Uses a shared in-memory track map to reconstruct domain [Track] objects
 * from Media3 [MediaItem]s without re-querying MediaStore.
 *
 * A periodic position ticker polls the player every [POSITION_UPDATE_INTERVAL_MS]
 * while playback is active, ensuring that the seek bar and mini-player progress
 * update smoothly in real time across all surfaces.
 *
 * **Session restoration** — when the controller first connects and finds an empty
 * player queue, [restorePlaybackSession] is launched automatically. It reads the
 * persisted state, re-hydrates [trackMap], loads lightweight [MediaItem]s, and
 * seeks to the saved position. Playback is intentionally left paused so the user
 * can resume when ready. If the user triggers [play] before restoration completes,
 * the restore is safely skipped via [commandMutex] guard.
 *
 * @property context Application context for building the MediaController.
 * @property mainScope Application-scoped main-thread coroutine scope for the position ticker and
 *   session restoration. Qualified with [ApplicationScope] to distinguish it unambiguously from
 *   the main-dispatcher binding in the DI graph.
 * @property ioDispatcher IO dispatcher used for the single per-play artwork thumbnail load,
 *   keeping the blocking content-resolver call off the main thread.
 * @property playbackPersistenceRepository Repository used to read and fetch tracks for the
 *   restored queue. Called directly here — the Data layer depends on the Domain repository
 *   interface, not on any use case wrapper.
 */
@Singleton
class PlaybackController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val mainScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val playbackPersistenceRepository: PlaybackPersistenceRepository
) {

    private var controller: MediaController? = null
    private val controllerMutex = Mutex()

    /**
     * Serializes all transport commands (play, pause, resume, seek, skip, mode changes)
     * so that rapid user taps never produce interleaved Media3 calls.
     *
     * Separate from [controllerMutex] because controller creation is a one-time operation
     * whereas command serialization is ongoing during playback.
     */
    private val commandMutex = Mutex()

    /** In-memory map of Media3 mediaId → domain [Track] for reverse-lookup. */
    private val trackMap = mutableMapOf<String, Track>()

    /** Active position ticker job — cancelled when playback pauses or stops. */
    private var positionTickerJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    /** Observable playback state stream. */
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _queueState = MutableStateFlow(QueueState.EMPTY)
    /** Observable queue state stream. */
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    init {
        // Eagerly connect to the playback service so that the persisted session is
        // restored as soon as the singleton is created (app startup), making the
        // mini-player and player screen show the last track in paused state without
        // requiring the user to issue a play command first.
        // runCatching swallows connection failures (e.g. service not yet ready) so
        // they do not crash the app; the controller will reconnect on the next command.
        mainScope.launch { runCatching { getController() } }
    }

    /**
     * Ensures a connected [MediaController] is available.
     *
     * Lazily builds the controller on first use and registers a [Player.Listener]
     * to forward state changes to the domain flows.
     *
     * @return The connected [MediaController].
     */
    private suspend fun getController(): MediaController = controllerMutex.withLock {
        controller?.let { return it }

        val sessionToken = SessionToken(
            context,
            ComponentName(context, AudioPlaybackService::class.java)
        )

        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        val newController = suspendCancellableCoroutine { continuation ->
            controllerFuture.addListener(
                {
                    runCatching { controllerFuture.get() }
                        .onSuccess { builtController ->
                            if (continuation.isActive) {
                                continuation.resume(builtController)
                            }
                        }
                        .onFailure { throwable ->
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        "Failed to connect to the playback service.",
                                        throwable
                                    )
                                )
                            }
                        }
                },
                ContextCompat.getMainExecutor(context)
            )

            continuation.invokeOnCancellation {
                controllerFuture.cancel(true)
            }
        }

        newController.addListener(createPlayerListener())
        controller = newController
        updatePlaybackState()
        updateQueueState()

        // If the player queue is empty (fresh start after force-close), asynchronously
        // restore the last session. The restoration runs on mainScope so it can call
        // MediaController APIs directly; it acquires commandMutex internally and will
        // no-op if a real play() command has already populated the queue.
        if (newController.mediaItemCount == 0) {
            mainScope.launch {
                restorePlaybackSession(
                    ctrl = newController,
                    commandMutex = commandMutex,
                    trackMap = trackMap,
                    playbackPersistenceRepository = playbackPersistenceRepository,
                    onUpdatePlaybackState = ::updatePlaybackState,
                    onUpdateQueueState = ::updateQueueState,
                )
            }
        }

        return newController
    }

    /**
     * Starts playback of [track] within the provided [queue].
     *
     * Converts domain tracks to Media3 [MediaItem]s, sets the playlist,
     * and seeks to the requested track index before starting playback.
     *
     * @param track The track to begin playing.
     * @param queue Full ordered list of tracks forming the playback queue.
     */
    suspend fun play(track: Track, queue: List<Track>) {
        require(queue.isNotEmpty()) { "Cannot start playback with an empty queue." }
        require(track.uri.isNotBlank()) { "Cannot start playback because the selected track URI is blank." }

        ensurePlaybackServiceStarted()
        val ctrl = getController()

        val startIndex = queue.indexOfFirst { it.id == track.id }
        require(startIndex >= 0) { "Selected track is not part of the provided queue." }

        // Populate the reverse-lookup map before touching Media3 so the UI can
        // immediately resolve the currently requested track.
        trackMap.replaceWithTracks(queue)

        // Build lightweight items for the full queue without any artwork I/O — this is
        // O(n) string allocations instead of N blocking ContentResolver thumbnail reads,
        // which was the primary cause of main-thread hangs on large libraries.
        val lightweightItems = queue.map { it.toRestorationMediaItem() }

        // Load embedded artwork only for the selected track on the IO dispatcher so the
        // single blocking ContentResolver call stays off the main thread and the UI frame
        // pipeline is never stalled. All other queue items keep the lightweight variant;
        // their artwork is not embedded in the Media3 metadata but the artworkUri is still
        // set so the system notification can attempt lazy loading when each item becomes current.
        val currentItemWithArt = withContext(ioDispatcher) {
            queue[startIndex].toPlaybackMediaItem(
                artworkBytes = PlaybackControllerMediaItemFactory.loadArtworkBytes(
                    context = context,
                    track = queue[startIndex],
                )
            )
        }

        val mediaItems = lightweightItems.toMutableList().also { it[startIndex] = currentItemWithArt }

        commandMutex.withLock {
            ctrl.setMediaItems(mediaItems, startIndex, 0L)
            _queueState.value = buildQueueStateSnapshot(
                ctrl = ctrl,
                tracks = queue,
                fallbackQueueState = _queueState.value,
                currentIndex = startIndex,
            )
            _playbackState.value = PlaybackState(
                status = PlaybackStatus.BUFFERING,
                currentTrack = track,
                positionMs = 0L,
                durationMs = track.durationMs,
                playbackSpeed = ctrl.playbackParameters.speed
            )
            ctrl.prepare()
            ctrl.play()
            updateQueueState()
            updatePlaybackState()
        }

        Log.d(TAG, "Playing: ${track.title} (index $startIndex of ${queue.size})")
    }

    /**
     * Inserts [track] immediately after the active item without interrupting playback.
     *
     * An empty queue accepts the track at position zero and remains paused until the
     * listener explicitly starts playback.
     *
     * @param track Track that should play next.
     */
    suspend fun playNext(track: Track) {
        val ctrl = getController()
        val insertionIndex = ctrl.currentMediaItemIndex
            .takeIf { it in 0 until ctrl.mediaItemCount }
            ?.plus(1)
            ?: 0
        insertQueueTrack(ctrl, track, insertionIndex)
    }

    /**
     * Inserts an ordered track group immediately after the active item.
     *
     * Media3 receives the complete list under one command lock so album order
     * cannot be reversed by repeated single-track insertions.
     *
     * @param tracks Tracks to insert in their supplied order.
     */
    suspend fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val ctrl = getController()
        val insertionIndex = ctrl.currentMediaItemIndex
            .takeIf { it in 0 until ctrl.mediaItemCount }
            ?.plus(1)
            ?: 0
        insertQueueTracks(ctrl, tracks, insertionIndex)
    }

    /**
     * Appends [track] to the active queue without interrupting playback.
     *
     * @param track Track that should become the final queue item.
     */
    suspend fun addToQueue(track: Track) {
        val ctrl = getController()
        insertQueueTrack(ctrl, track, ctrl.mediaItemCount)
    }

    /**
     * Appends an ordered track group to the active queue without interrupting playback.
     *
     * @param tracks Tracks to append in their supplied order.
     */
    suspend fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val ctrl = getController()
        insertQueueTracks(ctrl, tracks, ctrl.mediaItemCount)
    }

    /**
     * Repositions one queue item while preserving the active track and playhead.
     *
     * @param fromIndex Current zero-based queue position.
     * @param toIndex Target zero-based queue position.
     */
    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val ctrl = getController()
        commandMutex.withLock {
            require(fromIndex in 0 until ctrl.mediaItemCount) { "Queue source position is invalid." }
            require(toIndex in 0 until ctrl.mediaItemCount) { "Queue target position is invalid." }
            if (fromIndex == toIndex) return@withLock

            val current = _queueState.value
            val reorderedTracks = current.tracks.toMutableList().apply {
                if (fromIndex in indices && toIndex in indices) {
                    add(toIndex, removeAt(fromIndex))
                }
            }
            ctrl.moveMediaItem(fromIndex, toIndex)
            _queueState.value = current.copy(
                tracks = reorderedTracks,
                currentIndex = PlaybackQueueMutationResolver.currentIndexAfterMove(
                    currentIndex = current.currentIndex,
                    fromIndex = fromIndex,
                    toIndex = toIndex,
                ),
            )
        }
    }

    /**
     * Clears every queued item except the one that is currently playing.
     *
     * The current decoder, sink, and playhead are deliberately left untouched. The service-side
     * [PlaybackStateManager] persists the resulting one-track session through the timeline update.
     */
    suspend fun clearQueue() {
        val ctrl = getController()
        commandMutex.withLock {
            if (ctrl.mediaItemCount == 0) return@withLock
            val retainedCurrentItem = PlaybackQueueClearer.retainCurrentMediaItem(ctrl)
            if (retainedCurrentItem) {
                val currentMediaId = ctrl.currentMediaItem?.mediaId
                val retainedTrack = currentMediaId?.let(trackMap::get)
                    ?: _queueState.value.tracks.getOrNull(_queueState.value.currentIndex)
                trackMap.keys.retainAll(setOfNotNull(currentMediaId))
                _queueState.value = _queueState.value.copy(
                    tracks = listOfNotNull(retainedTrack),
                    currentIndex = if (retainedTrack == null) -1 else 0,
                )
                updatePlaybackState()
            } else {
                trackMap.clear()
                stopPositionTicker()
                _queueState.value = QueueState.EMPTY
                _playbackState.value = PlaybackState.IDLE
            }
        }
    }

    /** Adds one domain track to Media3 and publishes an immediate queue snapshot. */
    private suspend fun insertQueueTrack(
        ctrl: MediaController,
        track: Track,
        requestedIndex: Int
    ) = insertQueueTracks(ctrl, listOf(track), requestedIndex)

    /** Adds one ordered domain-track group to Media3 and publishes one queue snapshot. */
    private suspend fun insertQueueTracks(
        ctrl: MediaController,
        tracks: List<Track>,
        requestedIndex: Int
    ) {
        require(tracks.isNotEmpty()) { "Cannot queue an empty track collection." }
        require(tracks.all { track -> track.uri.isNotBlank() }) {
            "Cannot queue a track whose URI is blank."
        }
        commandMutex.withLock {
            val insertionIndex = requestedIndex.coerceIn(0, ctrl.mediaItemCount)
            val current = _queueState.value
            val updatedTracks = current.tracks.toMutableList().apply {
                addAll(insertionIndex.coerceIn(0, size), tracks)
            }
            tracks.forEach { track -> trackMap[track.id.toString()] = track }
            ctrl.addMediaItems(
                insertionIndex,
                tracks.map(Track::toRestorationMediaItem)
            )

            val updatedCurrentIndex = when {
                current.currentIndex < 0 -> 0
                insertionIndex <= current.currentIndex -> current.currentIndex + tracks.size
                else -> current.currentIndex
            }
            _queueState.value = current.copy(
                tracks = updatedTracks,
                currentIndex = updatedCurrentIndex,
            )
        }
    }

    /**
     * Pauses the currently playing track.
     *
     * Obtains the [MediaController] reference **before** acquiring [commandMutex] so that
     * the lock body contains no suspension points. A non-suspending lock body is immune to
     * coroutine cancellation between mutex acquisition and command dispatch, preventing
     * orphaned half-executed commands from corrupting the ExoPlayer state machine.
     *
     * The idempotency guard (`!ctrl.playWhenReady`) prevents duplicate pause commands when
     * the player is already paused, avoiding redundant state transitions that can trigger
     * a buffering deadlock on the offload audio pipeline.
     *
     * Uses `playWhenReady = false` rather than [Player.pause] for precise semantics —
     * [Player.pause] may trigger additional internal prepare/reset logic on some paths.
     */
    suspend fun pause() {
        val ctrl = getController()
        commandMutex.withLock {
            // Idempotency: skip if already paused; sending a duplicate pause into the offload
            // pipeline causes spurious BUFFERING transitions that are hard to recover from.
            if (!ctrl.playWhenReady) return@withLock
            ctrl.playWhenReady = false
            updatePlaybackState()
        }
    }

    /**
     * Resumes playback from the paused position.
     *
     * Mirrors the cancellation-safety design of [pause]: [getController] is resolved
     * before the lock so the critical section is free of suspension points.
     *
     * Uses `playWhenReady = true` rather than [Player.play] to avoid the implicit
     * `prepare()` call that [play] performs when the player is in `STATE_IDLE` during
     * normal rapid-toggle scenarios. However, after a long background period the player
     * can legitimately drift to `STATE_IDLE` — caused by audio-focus loss (another app
     * claiming focus) or the BT stack flushing the audio pipeline into standby. In that
     * case `playWhenReady = true` alone is a **no-op**: Media3 requires an explicit
     * `prepare()` to transition out of `STATE_IDLE`.
     *
     * ### Idempotency guard
     *
     * The skip condition requires `playWhenReady && isPlaying` — **not**
     * `playWhenReady` alone. A stalled player (engine parked in `STATE_ENDED` /
     * `STATE_IDLE` after a failed background auto-advance) can report a stale
     * `playWhenReady = true` while producing no audio; guarding on the intent flag
     * alone turned the play button into a permanent no-op in exactly that state.
     * Re-issuing `playWhenReady = true` is safe: the adapter's
     * `handleSetPlayWhenReady` recovery paths (IDLE/ERROR reload, ENDED restart)
     * are idempotent per press.
     */
    suspend fun resume() {
        ensurePlaybackServiceStarted()
        val ctrl = getController()
        commandMutex.withLock {
            // Idempotency: skip only when audio is genuinely flowing.
            if (ctrl.playWhenReady && ctrl.isPlaying) return@withLock
            ctrl.playWhenReady = true

            // Controller-side recovery for STATE_IDLE, because `playWhenReady = true`
            // alone cannot move the player out of it. ENDED/ERROR recovery lives in
            // the adapter's handleSetPlayWhenReady, which reloads the engine pipeline.
            if (ctrl.playbackState == Player.STATE_IDLE && ctrl.mediaItemCount > 0) {
                ctrl.prepare()
                Log.d(TAG, "Resume from STATE_IDLE — calling prepare() to rebuild audio pipeline")
            }

            updatePlaybackState()
        }
    }

    /**
     * Starts the playback service only for user-initiated playback actions.
     *
     * Passive controller work such as state observation or session restoration must NOT call
     * [ContextCompat.startForegroundService], because that API requires the service to call
     * `startForeground()` almost immediately. [AudioPlaybackService] relies on Media3 to enter
     * foreground when actual playback starts, so eagerly starting it during controller bootstrap
     * can trigger the ANR: "Context.startForegroundService() did not then call Service.startForeground()".
     *
     * For observer/bootstrap paths, [MediaController.Builder] binding is sufficient to create and
     * connect to the session without foreground promotion. Only explicit play/resume commands should
     * use this method.
     */
    private fun ensurePlaybackServiceStarted() {
        val serviceIntent = Intent(context, AudioPlaybackService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    /**
     * Seeks to the specified position within the current track.
     *
     * @param positionMs Target position in milliseconds.
     */
    suspend fun seekTo(positionMs: Long) {
        val ctrl = getController()
        commandMutex.withLock {
            ctrl.seekTo(positionMs)
            emitOptimisticSeekPosition(positionMs)
        }
    }

    /**
     * Advances to the next track in the queue and ensures playback starts.
     *
     * Calls [Player.play] after the seek so that skip always begins playing
     * the new track regardless of the previous [Player.playWhenReady] state —
     * matching the behaviour users expect from Spotify, Apple Music, and similar apps
     * where pressing skip implies "play the next song", not "advance while staying paused".
     */
    suspend fun skipNext() {
        ensurePlaybackServiceStarted()
        val ctrl = getController()
        commandMutex.withLock {
            ctrl.seekToNext()
            ctrl.play()
            updatePlaybackState()
            updateQueueState()
        }
    }

    /**
     * Returns to the previous track in the queue and ensures playback starts.
     *
     * Calls [Player.play] after the seek so that skip-previous always begins playing
     * the new track regardless of the previous [Player.playWhenReady] state —
     * matching the behaviour users expect from Spotify, Apple Music, and similar apps.
     */
    suspend fun skipPrevious() {
        ensurePlaybackServiceStarted()
        val ctrl = getController()
        commandMutex.withLock {
            ctrl.seekToPrevious()
            // Explicitly start playback after the seek — seekToPrevious() does not change
            // playWhenReady, so a paused player would stay paused on the new track.
            ctrl.play()
            updatePlaybackState()
            updateQueueState()
        }
    }

    /**
     * Sets the repeat mode on the player.
     *
     * @param mode Domain [RepeatMode] to apply.
     */
    suspend fun setRepeatMode(mode: RepeatMode) {
        val ctrl = getController()
        commandMutex.withLock {
            ctrl.repeatMode = PlaybackStateMapper.toMedia3RepeatMode(mode)
            updateQueueState()
        }
    }

    /**
     * Sets the shuffle mode on the player.
     *
     * @param mode Domain [ShuffleMode] to apply.
     */
    suspend fun setShuffleMode(mode: ShuffleMode) {
        val ctrl = getController()
        commandMutex.withLock {
            ctrl.shuffleModeEnabled = PlaybackStateMapper.toMedia3ShuffleEnabled(mode)
            updateQueueState()
        }
    }

    /**
     * Creates a [Player.Listener] that maps Media3 callbacks to domain state flows.
     *
     * Manages the position ticker lifecycle: starts polling when playback becomes
     * active and cancels when it pauses or an error occurs.
     */
    private fun createPlayerListener(): Player.Listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlaybackState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlaybackState()
            if (!playWhenReady) {
                stopPositionTicker()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState()
            // Start or stop the periodic position ticker based on playing state
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
            }
        }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePlaybackState()
                updateQueueState()

                // Enrich the incoming item with embedded artwork bytes so the
                // system notification displays album art on all OEM ROMs, including
                // those that cannot resolve content:// URIs from outside the app.
                mediaItem?.let {
                    enrichCurrentMediaItemArtwork(
                        mediaItem = it,
                        controllerRef = controller,
                        trackMap = trackMap,
                        mainScope = mainScope,
                        ioDispatcher = ioDispatcher,
                        context = context,
                    )
                }
            }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            updatePlaybackState()
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                updateQueueState()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateQueueState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateQueueState()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            updatePlaybackState()
            updateQueueState()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            stopPositionTicker()
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR
            )
            Log.e(TAG, "Player error: ${error.message}")
        }
    }

    /** Reads the current player state and emits a domain [PlaybackState] snapshot. */
    private fun updatePlaybackState() {
        val ctrl = controller ?: return
        val currentTrack = resolveCurrentTrack(
            ctrl = ctrl,
            trackMap = trackMap,
            fallbackState = _playbackState.value,
        )

        val resolvedStatus = when {
            ctrl.playWhenReady && ctrl.playbackState == Player.STATE_IDLE && currentTrack != null -> PlaybackStatus.BUFFERING
            else -> PlaybackStateMapper.toPlaybackStatus(ctrl.playbackState, ctrl.isPlaying)
        }

        _playbackState.value = PlaybackState(
            status = resolvedStatus,
            currentTrack = currentTrack,
            positionMs = ctrl.currentPosition.coerceAtLeast(0),
            durationMs = resolveDurationMs(ctrl, currentTrack),
            playbackSpeed = ctrl.playbackParameters.speed
        )
    }

    /**
     * Publishes the requested seek target immediately so UI surfaces do not
     * wait for the next controller poll or Media3 callback to move the thumb.
     *
     * The audiophile engine performs seek work on its dedicated audio thread,
     * so reading [MediaController.currentPosition] synchronously right after
     * dispatching `seekTo` can briefly return the pre-seek or reset position.
     * Emitting the target position here keeps the player and mini-player
     * responsive until the authoritative controller snapshot arrives.
     */
    private fun emitOptimisticSeekPosition(positionMs: Long) {
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        val currentState = _playbackState.value
        _playbackState.value = currentState.copy(
            positionMs = targetPositionMs,
            durationMs = resolveDurationMs(
                ctrl = controller,
                fallbackTrack = currentState.currentTrack,
                preferredDurationMs = currentState.durationMs,
            ),
        )
    }

    /** Reads the current queue and emits a domain [QueueState] snapshot. */
    private fun updateQueueState() {
        val ctrl = controller ?: return
        val tracks = (0 until ctrl.mediaItemCount).mapNotNull { index ->
            val item = ctrl.getMediaItemAt(index)
            trackMap[item.mediaId]
        }.ifEmpty { _queueState.value.tracks }

        _queueState.value = buildQueueStateSnapshot(
            ctrl = ctrl,
            tracks = tracks,
            fallbackQueueState = _queueState.value,
        )
    }



    /**
     * Starts a periodic coroutine that polls the player's current position
     * every [POSITION_UPDATE_INTERVAL_MS] and emits updated [PlaybackState]
     * snapshots.
     *
     * Runs on the main thread because [MediaController] APIs are main-thread-bound.
     * Any existing ticker is cancelled before starting a new one.
     */
    private fun startPositionTicker() {
        positionTickerJob = mainScope.restartTicker(
            currentJob = positionTickerJob,
            intervalMs = POSITION_UPDATE_INTERVAL_MS,
            onTick = ::updatePlaybackState,
        )
    }

    /**
     * Cancels the active position ticker coroutine.
     */
    private fun stopPositionTicker() {
        positionTickerJob = positionTickerJob.cancelAndClear()
    }

    private companion object {
        const val TAG = "PlaybackController"

        /**
         * Interval between position polling ticks in milliseconds.
         *
         * 300 ms provides smooth seek-bar progression (~3.3 updates/sec) without
         * excessive state emissions.
         */
        const val POSITION_UPDATE_INTERVAL_MS = 300L
    }
}
