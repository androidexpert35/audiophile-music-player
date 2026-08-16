package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.platform.LocalView

/**
 * Creates reusable drag state bound to a plain, fixed-size `Column` list.
 *
 * Reimplements the same drag-and-reorder algorithm as
 * [com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.TrackReorderState]
 * — long-press start, crossing-distance-based neighbour swap, haptic feedback on both
 * start and swap — but tracks each row's live bounds via [Modifier.onGloballyPositioned]
 * instead of `LazyListState.layoutInfo`, so it works for the small, non-virtualized
 * settings lists that must stay off `LazyColumn` per this app's fixed-layout convention.
 *
 * @param itemCount Number of reorderable rows in the list.
 * @return Gesture state that keeps the dragged row attached to the listener's finger.
 */
@Composable
internal fun rememberFixedListReorderState(itemCount: Int): FixedListReorderState {
    val hapticView = LocalView.current
    return remember(itemCount, hapticView) { FixedListReorderState(hapticView) }
}

/**
 * Owns temporary drag gesture state for a small, fixed-size `Column` list.
 */
internal class FixedListReorderState(
    private val hapticView: View,
) {
    private val itemBounds = mutableStateMapOf<Int, Rect>()
    private var draggedIndex by mutableIntStateOf(-1)
    private var dragTranslationY by mutableFloatStateOf(0f)

    /** Returns whether the row at [index] is currently floating above the list. */
    fun isDragged(index: Int): Boolean = draggedIndex == index

    /** Returns the active floating translation only for the dragged row. */
    fun translationFor(index: Int): Float = if (isDragged(index)) dragTranslationY else 0f

    /** Records the row's current on-screen bounds, refreshed on every layout pass. */
    fun onItemPositioned(index: Int, coordinates: LayoutCoordinates) {
        itemBounds[index] = coordinates.boundsInParent()
    }

    /**
     * Creates the pointer handler attached exclusively to a leading drag handle.
     *
     * @param index Row's current position in the list.
     * @param onMove Callback receiving the source and target indices once a drag
     *   crosses into a neighbouring row.
     * @return Modifier handling long-press drag gestures.
     */
    fun dragHandleModifier(
        index: Int,
        onMove: (Int, Int) -> Unit,
    ): Modifier = Modifier.pointerInput(index) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                draggedIndex = index
                dragTranslationY = 0f
                hapticView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            },
            onDragCancel = ::finishDrag,
            onDragEnd = ::finishDrag,
            onDrag = { change, dragAmount ->
                change.consume()
                val sourceIndex = draggedIndex.takeIf { it >= 0 }
                    ?: return@detectDragGesturesAfterLongPress
                dragTranslationY += dragAmount.y
                val sourceBounds = itemBounds[sourceIndex]
                    ?: return@detectDragGesturesAfterLongPress
                val targetCenter = sourceBounds.top + sourceBounds.height / 2 + dragTranslationY
                val (targetIndex, targetBounds) = itemBounds.entries.firstOrNull { (_, bounds) ->
                    targetCenter >= bounds.top && targetCenter <= bounds.bottom
                }?.toPair() ?: return@detectDragGesturesAfterLongPress
                if (targetIndex != sourceIndex) {
                    onMove(sourceIndex, targetIndex)
                    hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    // Compensate for the row changing its base slot so it stays under
                    // the finger while sibling rows snap into the open position.
                    dragTranslationY += sourceBounds.top - targetBounds.top
                    draggedIndex = targetIndex
                }
            }
        )
    }

    /** Clears the temporary lift state after a drop or cancelled gesture. */
    private fun finishDrag() {
        draggedIndex = -1
        dragTranslationY = 0f
    }
}
