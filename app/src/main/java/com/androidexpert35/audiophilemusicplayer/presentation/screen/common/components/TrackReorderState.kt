package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Creates reusable drag state bound to one track-list scroll container.
 *
 * @param listState Scroll state containing the reorderable track rows.
 * @return Gesture state that keeps the dragged row attached to the listener's finger.
 */
@Composable
internal fun rememberTrackReorderState(listState: LazyListState): TrackReorderState {
    val hapticView = LocalView.current
    return remember(listState, hapticView) { TrackReorderState(listState, hapticView) }
}

/**
 * Owns temporary drag gesture state shared by editable track lists.
 */
internal class TrackReorderState(
    private val listState: LazyListState,
    private val hapticView: View
) {
    private var draggedListIndex by mutableIntStateOf(-1)
    private var draggedEntryKey by mutableStateOf<String?>(null)
    private var crossingDistance by mutableFloatStateOf(0f)
    private var dragTranslationY by mutableFloatStateOf(0f)

    /** Returns whether the supplied track entry is currently floating above the list. */
    fun isDragged(entryKey: String): Boolean = draggedEntryKey == entryKey

    /** Returns the active floating translation only for the selected entry. */
    fun translationFor(entryKey: String): Float = if (isDragged(entryKey)) dragTranslationY else 0f

    /**
     * Creates the pointer handler attached exclusively to a leading drag handle.
     *
     * @param entryKey Movement-stable key of the row.
     * @param firstTrackListIndex Absolute index of the first reorderable row.
     * @param lastTrackListIndex Absolute index of the last reorderable row.
     * @param onMove Callback receiving track-relative source and target indices.
     * @return Modifier handling long-press drag gestures.
     */
    fun dragHandleModifier(
        entryKey: String,
        firstTrackListIndex: Int,
        lastTrackListIndex: Int,
        onMove: (Int, Int) -> Unit
    ): Modifier = Modifier.pointerInput(entryKey, firstTrackListIndex, lastTrackListIndex) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                // Resolve the row's live lazy-list index only when a new gesture
                // actually begins. The row can cross several neighbours afterward,
                // but the pointer-input coroutine remains keyed solely to entryKey
                // and is therefore not cancelled when that index changes.
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { item -> item.key == entryKey }
                    ?.index
                    ?.let { liveListIndex ->
                        draggedListIndex = liveListIndex
                        draggedEntryKey = entryKey
                        crossingDistance = 0f
                        dragTranslationY = 0f
                        hapticView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
            },
            onDragCancel = ::finishDrag,
            onDragEnd = ::finishDrag,
            onDrag = { change, dragAmount ->
                change.consume()
                val sourceListIndex = draggedListIndex.takeIf { it >= firstTrackListIndex }
                    ?: return@detectDragGesturesAfterLongPress
                crossingDistance += dragAmount.y
                dragTranslationY += dragAmount.y
                val sourceItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == sourceListIndex }
                    ?: return@detectDragGesturesAfterLongPress
                val targetCenter = sourceItem.offset + sourceItem.size / 2 + crossingDistance
                val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.index in firstTrackListIndex..lastTrackListIndex &&
                        targetCenter >= item.offset && targetCenter <= item.offset + item.size
                } ?: return@detectDragGesturesAfterLongPress
                if (targetItem.index != sourceListIndex) {
                    onMove(
                        sourceListIndex - firstTrackListIndex,
                        targetItem.index - firstTrackListIndex
                    )
                    hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    // Compensate for the row changing its base slot so it stays under
                    // the finger while sibling rows animate around the open position.
                    dragTranslationY += (sourceItem.offset - targetItem.offset).toFloat()
                    draggedListIndex = targetItem.index
                    crossingDistance = 0f
                }
            }
        )
    }

    /** Clears the temporary lift state after a drop or cancelled gesture. */
    private fun finishDrag() {
        draggedListIndex = -1
        draggedEntryKey = null
        crossingDistance = 0f
        dragTranslationY = 0f
    }
}

/**
 * Builds unique movement-stable keys for track lists that may contain duplicates.
 *
 * @receiver Tracks in their current visible order.
 * @return One stable key per visible entry.
 */
internal fun List<Track>.toStableTrackEntryKeys(): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return map { track ->
        val occurrence = occurrences.getOrDefault(track.uri, 0)
        occurrences[track.uri] = occurrence + 1
        "${track.uri}_$occurrence"
    }
}
