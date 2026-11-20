/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFold
import androidx.compose.ui.util.fastForEach
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard3.EditorSelection
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.OperationUnit
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKeyboard
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Duration

sealed interface TrackedPointer {
    val id: PointerId
    val down: PointerInputChange
    val downKey: TouchKey

    data class Peek(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val downLayerId: K3LayerId,
        val downInputShiftState: InputShiftState,
        val peekLayerId: K3LayerId,
        val peekKey: TouchKey?,
        val peekLine: PeekLine?,
        val peekMustSwitchBack: Boolean,
    ) : TrackedPointer

    data class Output(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val longPress: LongPress,
        val repeatJob: Job?,
        val directLongPressActionEmitted: Boolean = false,
        val repeatEmitted: Boolean = false,
    ) : TrackedPointer
}

data class TrackedMultiTapSeq(
    val touchKey: TouchKey,
    val multiTapIndex: Int,
    val commitJob: Job?,
    val commit: suspend () -> Unit,
    val cycleNext: suspend () -> Unit,
)

data class PeekLine(
    val start: Offset,
    val end: Offset,
)

/**
 * State for the Gboard-style swipe-to-delete gesture. Text is only selected while the finger
 * moves; deletion is committed on lift, so reversing the swipe always restores the text.
 */
data class DeleteSwipe(
    val start: Offset,
    val action: SwipeAction,
    val unitPx: Float,
    val baseSelection: EditorSelection,
    val baseTextBefore: String,
    val currentSelection: EditorSelection? = null,
    val unitCount: Int = 0,
    val active: Boolean = false,
)

enum class SpaceBarSwipeDirection {
    LEFT,
    RIGHT,
    UP,
}

/**
 * State for a spacebar swipe. Cursor movements are applied incrementally while the finger moves,
 * while all other configured actions are deferred until the finger is lifted.
 */
data class SpaceBarSwipe(
    val start: Offset,
    val unitPx: Float,
    val thresholdPx: Float,
    val direction: SpaceBarSwipeDirection? = null,
    val lastPosition: Offset = start,
    val unitCount: Int = 0,
    val executedCount: Int = 0,
    val active: Boolean = false,
)

private fun SwipeAction.supportsDeleteSwipe(): Boolean = when (this) {
    SwipeAction.DELETE_CHARACTERS_PRECISELY,
    SwipeAction.SELECT_CHARACTERS_PRECISELY,
    SwipeAction.DELETE_WORDS_PRECISELY,
    SwipeAction.SELECT_WORDS_PRECISELY -> true
    else -> false
}

private fun SwipeAction.usesWordUnits(): Boolean = when (this) {
    SwipeAction.DELETE_WORDS_PRECISELY,
    SwipeAction.SELECT_WORDS_PRECISELY -> true
    else -> false
}

private fun SwipeAction.commitsDeletion(): Boolean = when (this) {
    SwipeAction.DELETE_CHARACTERS_PRECISELY,
    SwipeAction.DELETE_WORDS_PRECISELY -> true
    else -> false
}

private data class LastUpKey(
    val touchKey: TouchKey,
    val uptimeMillis: Long,
)

private val ASCII_SPACE = " ".asK3String()

private const val GESTURE_FEEDBACK_STEP = 4

private fun TouchKey.isSpaceKey(): Boolean = attrs.output == ASCII_SPACE

// TODO the sync of this class is a bit messy
class PointerTracker(
    val touchKeyboard: TouchKeyboard,
    val imeController: ImeController,
    val interactionController: InteractionController,
    val showSimplePopups: Boolean,
    val peekDistanceSqMin: Float,
    val deleteSwipeAction: SwipeAction,
    val deleteSwipeUnitPx: Float,
    val spaceBarSwipeUnitPx: Float,
    val spaceBarSwipeThresholdPx: Float,
    val gestureSlopPx: Float,
) {
    private val prefs by FlorisPreferenceStore
    private val mutationGuard = Mutex()

    val trackedPeekPointer: StateFlow<TrackedPointer.Peek?>
        field = MutableStateFlow(null)

    val trackedOutputPointer: StateFlow<TrackedPointer.Output?>
        field = MutableStateFlow(null)

    val trackedMultiTapSeq: StateFlow<TrackedMultiTapSeq?>
        field = MutableStateFlow(null)

    private var lastUpKeyState: LastUpKey? = null

    // Gesture state is intentionally not part of trackedOutputPointer: updating that StateFlow on
    // every pointer move recomposes every key in the keyboard and is the main source of swipe lag.
    private var activeDeleteSwipe: DeleteSwipe? = null
    private var activeSpaceBarSwipe: SpaceBarSwipe? = null

    suspend fun mutateLocked(action: suspend PointerTracker.() -> Unit) {
        mutationGuard.withLock { action() }
    }

    context(scope: CoroutineScope)
    suspend fun handleDown(down: PointerInputChange, size: IntSize) {
        val lastUpKey = lastUpKeyState

        trackedPeekPointer.value?.let { trackedPointer ->
            if (trackedPointer.peekKey != null) {
                handleUp(down.copy(id = trackedPointer.id), size)
            }
        }
        trackedOutputPointer.value?.let { trackedPointer ->
            handleUp(down.copy(id = trackedPointer.id), size)
        }

        val downLayerId = imeController.snapshotState().touchLayerId
        val downKey = touchKeyboard.findKey(downLayerId, down.position.normalized(size)) ?: return

        val timingOptions = interactionController.activeTimingOptions.value
        val keyRepeatTimeout = timingOptions.getKeyRepeatTimeout(downKey.attrs.output)
        val keyRepeatDelay = timingOptions.getKeyRepeatDelay(downKey.attrs.output)
        val longPressTimeout = timingOptions.getLongPressTimeout(downKey.attrs.output)
        val doubleTapTimeout = timingOptions.doubleTapTimeout

        val peekLayerId = downKey.attrs.layerId
        val isDeleteSwipeKey = downKey.attrs.output == ImeActions.Backspace &&
            deleteSwipeAction.supportsDeleteSwipe() &&
            !imeController.snapshotState().editor.info.isRawInputEditor
        val isSpaceBarKey = downKey.isSpaceKey()
        if (peekLayerId != null) {
            // this is a peek key action
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commit()
            }
            val downInputShiftState: InputShiftState
            imeController.updateState {
                downInputShiftState = state.flags.inputShiftState
                if (downKey.isShiftKey) {
                    val isDoubleTap = lastUpKey != null && lastUpKey.touchKey.isShiftKey &&
                        (down.uptimeMillis - lastUpKey.uptimeMillis) < doubleTapTimeout.inWholeMilliseconds
                    cycleInputShiftState(isDoubleTap)
                } else {
                    switchTouchLayer(peekLayerId)
                }
            }
            trackedPeekPointer.value = TrackedPointer.Peek(
                id = down.id,
                down = down,
                downKey = downKey,
                downLayerId = downLayerId,
                downInputShiftState = downInputShiftState,
                peekLayerId = peekLayerId,
                peekKey = null,
                peekLine = null,
                peekMustSwitchBack = false,
            )
            interactionController.performFeedback(InteractionKind.KeyPress)
        } else {
            activeDeleteSwipe = if (isDeleteSwipeKey) {
                val editor = imeController.snapshotState().editor
                val baseSelection = editor.getSelectionRange()
                val baseTextBefore = editor.getTextBeforeSelection()
                if (baseSelection != null && baseTextBefore != null) {
                    DeleteSwipe(
                        start = down.position,
                        action = deleteSwipeAction,
                        unitPx = deleteSwipeUnitPx,
                        baseSelection = baseSelection,
                        baseTextBefore = baseTextBefore,
                    )
                } else {
                    null
                }
            } else {
                null
            }
            activeSpaceBarSwipe = if (isSpaceBarKey) {
                SpaceBarSwipe(
                    start = down.position,
                    unitPx = spaceBarSwipeUnitPx,
                    thresholdPx = spaceBarSwipeThresholdPx,
                )
            } else {
                null
            }
            trackedOutputPointer.value = TrackedPointer.Output(
                id = down.id,
                down = down,
                downKey = downKey,
                longPress = if (downKey.isSuitableForPopup || downKey.directLongPressAction != null || isSpaceBarKey) {
                    val anchorBounds = downKey.bounds.let { bounds ->
                        // TODO this is only tested for PHONE_PORTRAIT, test+fix other form factors!
                        val w = 0.1f
                        val h = bounds.height * 0.9f
                        val x = bounds.bottomCenter.x - w / 2f
                        val y = bounds.bottom - h * 2.3f
                        Rect(
                            offset = Offset(x, y),
                            size = Size(w, h),
                        )
                    }
                    LongPress(
                        anchorBounds = anchorBounds,
                        simpleBounds = if (showSimplePopups && downKey.isSuitableForSimplePopup) {
                            anchorBounds.copy(
                                bottom = anchorBounds.bottom + anchorBounds.height * 1.2f,
                            )
                        } else Rect.Zero,
                        simpleLabel = downKey.display,
                        simpleIndicateExtended = downKey.isSuitableForExtendedPopup,
                        extendedBounds = Rect.Zero,
                        extendedKeys = downKey.extendedPopupKeys,
                        extendedFocusedIndex = 0,
                        extendedJob = if (downKey.isSuitableForExtendedPopup || downKey.directLongPressAction != null || isSpaceBarKey) {
                            launchExtendedLongPressJob(longPressTimeout)
                        } else null,
                    )
                } else LongPress.None,
                repeatJob = if (downKey.isRepeatable) {
                    scope.launch {
                        delay(keyRepeatTimeout)
                        while (isActive) {
                            imeController.updateState {
                                downKey.attrs.output?.let { emit(it) }
                            }
                            trackedOutputPointer.value?.takeIf { it.id == down.id }?.let { current ->
                                trackedOutputPointer.value = current.copy(repeatEmitted = true)
                            }
                            interactionController.performFeedback(InteractionKind.KeyRepeat)
                            delay(keyRepeatDelay)
                        }
                    }
                } else null,
            )
            trackedMultiTapSeq.value.let { multiTapSeq ->
                if (multiTapSeq != null) {
                    if (multiTapSeq.touchKey !== downKey) {
                        multiTapSeq.commit()
                    } else if (downKey.multiTapKeys.isNotEmpty()) {
                        multiTapSeq.cycleNext()
                    }
                } else if (downKey.multiTapKeys.isNotEmpty()) {
                    val commit = suspend commit@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@commit
                        val multiTapKey = multiTapSeq.touchKey.multiTapKeys.getOrNull(multiTapSeq.multiTapIndex)
                            ?: return@commit
                        val output = multiTapKey.data.output ?: return@commit
                        imeController.updateState {
                            emit(output)
                        }
                        trackedMultiTapSeq.value = null
                    }
                    val cycleNext = suspend cycleNext@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@cycleNext
                        multiTapSeq.commitJob?.cancel()
                        val newIndex = (multiTapSeq.multiTapIndex + 1) % multiTapSeq.touchKey.multiTapKeys.size.fastCoerceAtLeast(1)
                        trackedMultiTapSeq.value = multiTapSeq.copy(
                            multiTapIndex = newIndex,
                            commitJob = null,
                        )
                    }
                    trackedMultiTapSeq.value = TrackedMultiTapSeq(
                        touchKey = downKey,
                        multiTapIndex = 0,
                        commitJob = null,
                        commit = commit,
                        cycleNext = cycleNext,
                    )
                }
            }
            interactionController.performFeedback(InteractionKind.KeyPress)
        }
    }

    suspend fun handleMove(move: PointerInputChange, size: IntSize) {
        trackedPeekPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            val distanceSq = (move.position - trackedPointer.down.position).getDistanceSquared()
            if (trackedPointer.peekLine != null || distanceSq >= peekDistanceSqMin) {
                val newPeekKey = touchKeyboard.findKey(trackedPointer.peekLayerId, move.position.normalized(size))
                trackedPeekPointer.value = trackedPointer.copy(
                    peekKey = newPeekKey,
                    peekLine = PeekLine(trackedPointer.down.position, move.position),
                    peekMustSwitchBack = true,
                )
            }
        }
        trackedOutputPointer.value?.takeIf { it.id == move.id }?.let { pointerAtMove ->
            var trackedPointer = pointerAtMove
            val swipeCandidate = activeDeleteSwipe != null || activeSpaceBarSwipe != null
            if (swipeCandidate &&
                (move.position - pointerAtMove.down.position).getDistanceSquared() >= gestureSlopPx * gestureSlopPx
            ) {
                // Kill long-press popups and key repeat as soon as a real swipe starts. Waiting for
                // unitCount/slop recognition let these jobs fire first and either blocked spacebar
                // swipe updates entirely (directLongPressActionEmitted) or deleted text behind the
                // delete-swipe selection.
                val longPressJob = pointerAtMove.longPress.extendedJob
                val repeatJob = pointerAtMove.repeatJob
                if (longPressJob?.isActive == true || repeatJob?.isActive == true) {
                    longPressJob?.cancel()
                    repeatJob?.cancel()
                    trackedPointer = pointerAtMove.copy(longPress = LongPress.None, repeatJob = null)
                    trackedOutputPointer.value = trackedPointer
                }
            }
            if (activeDeleteSwipe != null) {
                updateDeleteSwipe(move)
                return@let
            }
            if (activeSpaceBarSwipe != null) {
                updateSpaceBarSwipe(trackedPointer, move)
                return@let
            }
            val longPress = trackedPointer.longPress
            if (longPress.shouldShowExtendedPopup()) {
                val position = move.position.normalized(size).let { position ->
                    Offset(
                        x = (position.x)
                            .fastCoerceIn(longPress.extendedBounds.left, longPress.extendedBounds.right),
                        y = (position.y - longPress.anchorBounds.height)
                            .fastCoerceIn(longPress.extendedBounds.top, longPress.extendedBounds.bottom - 0.01f),
                    )
                }
                val newFocusedIndex = longPress.getNearestKeyIndex(position)
                if (newFocusedIndex != longPress.extendedFocusedIndex) {
                    trackedOutputPointer.value = trackedPointer.copy(
                        longPress = longPress.copy(extendedFocusedIndex = newFocusedIndex),
                    )
                }
            }
        }
    }

    private suspend fun updateDeleteSwipe(
        move: PointerInputChange,
        force: Boolean = false,
    ) {
        val swipe = activeDeleteSwipe ?: return
        val distancePx = max(0f, swipe.start.x - move.position.x)
        val usesWords = swipe.action.usesWordUnits()
        // Characters advance one at a time; words use a wider unit so a word swipe is not
        // uncontrollably fast with the fine character unit.
        val effectiveUnitPx = if (usesWords) swipe.unitPx * 4f else swipe.unitPx
        val unitCount = max(0, floor(distancePx / effectiveUnitPx).toInt())
        // The baseline text is captured at touch-down, so selection is always measured against a
        // fixed string and never has to re-read a selection that is itself moving.
        if (!force && unitCount == swipe.unitCount) {
            return
        }
        val unit = if (usesWords) OperationUnit.WORDS else OperationUnit.CHARACTERS
        val targetSelection = imeController.snapshotState().editor.selectionBefore(
            count = unitCount,
            unit = unit,
            base = swipe.baseSelection,
            textBefore = swipe.baseTextBefore,
        )

        if (!force && unitCount / GESTURE_FEEDBACK_STEP != swipe.unitCount / GESTURE_FEEDBACK_STEP) {
            interactionController.performFeedback(InteractionKind.GestureSwipe)
        }
        imeController.updateState {
            updateSelectionFromGesture(targetSelection, syncState = false)
        }

        activeDeleteSwipe = swipe.copy(
            currentSelection = targetSelection,
            unitCount = unitCount,
            active = swipe.active || unitCount > 0,
        )
    }

    private suspend fun updateSpaceBarSwipe(
        trackedPointer: TrackedPointer.Output,
        move: PointerInputChange,
    ) {
        // A long-press that already fired must no longer block a swipe which started afterwards;
        // the early-move cancellation in handleMove normally prevents this anyway.
        if (trackedPointer.repeatEmitted) return
        val swipe = activeSpaceBarSwipe ?: return
        val dx = move.position.x - swipe.start.x
        val dy = move.position.y - swipe.start.y
        val absX = abs(dx)
        val absY = abs(dy)

        // Follow the finger instead of locking the first direction. Hysteresis only flips once the
        // finger is clearly past the threshold on the other side, so it cannot flap near the start.
        val currentDirection = swipe.direction
        val direction = when (currentDirection) {
            null -> when {
                max(absX, absY) < swipe.thresholdPx -> return
                absX >= absY -> if (dx < 0f) SpaceBarSwipeDirection.LEFT else SpaceBarSwipeDirection.RIGHT
                dy < 0f -> SpaceBarSwipeDirection.UP
                else -> return // downward swipes are intentionally ignored
            }
            SpaceBarSwipeDirection.UP -> when {
                absY >= swipe.thresholdPx && absY >= absX -> SpaceBarSwipeDirection.UP
                absX >= swipe.thresholdPx && absX > absY -> {
                    if (dx < 0f) SpaceBarSwipeDirection.LEFT else SpaceBarSwipeDirection.RIGHT
                }
                else -> SpaceBarSwipeDirection.UP
            }
            else -> when {
                dx <= -swipe.thresholdPx -> SpaceBarSwipeDirection.LEFT
                dx >= swipe.thresholdPx -> SpaceBarSwipeDirection.RIGHT
                absY >= swipe.thresholdPx && absY > absX && dy < 0f -> SpaceBarSwipeDirection.UP
                else -> currentDirection
            }
        }
        val action = when (direction) {
            SpaceBarSwipeDirection.LEFT -> prefs.gestures.spaceBarSwipeLeft.get()
            SpaceBarSwipeDirection.RIGHT -> prefs.gestures.spaceBarSwipeRight.get()
            SpaceBarSwipeDirection.UP -> prefs.gestures.spaceBarSwipeUp.get()
        }
        val isCursorMovement =
            direction == SpaceBarSwipeDirection.LEFT && action == SwipeAction.MOVE_CURSOR_LEFT ||
                direction == SpaceBarSwipeDirection.RIGHT && action == SwipeAction.MOVE_CURSOR_RIGHT

        var newLastPosition = move.position
        var executedCount = swipe.executedCount
        if (isCursorMovement) {
            // Use signed movement since the last processed position. Going left, reversing, and
            // going left again now moves the cursor left, right, left instead of always left.
            val deltaPx = move.position.x - swipe.lastPosition.x
            val steps = floor(abs(deltaPx) / swipe.unitPx).toInt()
            if (steps > 0) {
                val sign = if (deltaPx < 0f) -1 else 1
                val moveAction = if (sign < 0) SwipeAction.MOVE_CURSOR_LEFT else SwipeAction.MOVE_CURSOR_RIGHT
                imeController.executeSwipeAction(moveAction, steps)
                newLastPosition = Offset(
                    x = swipe.lastPosition.x + sign * steps * swipe.unitPx,
                    y = swipe.lastPosition.y,
                )
                executedCount += sign * steps
            } else {
                newLastPosition = swipe.lastPosition
            }
        }

        // Net distance in the current direction gives the count for actions applied on lift, so
        // reversing the swipe correctly reduces the amount that will be deleted/selected.
        val netDistance = when (direction) {
            SpaceBarSwipeDirection.LEFT -> -dx
            SpaceBarSwipeDirection.RIGHT -> dx
            SpaceBarSwipeDirection.UP -> -dy
        }
        val unitCount = max(0, floor(netDistance / swipe.unitPx).toInt())
        // handleMove already cancelled long-press/repeat when the slop was exceeded. Keep the
        // gesture in a plain field so pointer moves do not recompose every key on the keyboard.
        activeSpaceBarSwipe = swipe.copy(
            direction = direction,
            lastPosition = newLastPosition,
            unitCount = unitCount,
            executedCount = executedCount,
            active = swipe.active || direction == SpaceBarSwipeDirection.UP || action != SwipeAction.NO_ACTION,
        )
    }

    /**
     * Finishes a spacebar swipe and returns whether the normal space output must be suppressed.
     */
    private suspend fun finishSpaceBarSwipe(swipe: SpaceBarSwipe): Boolean {
        // All non-cursor actions are applied here with the total travelled unit count, so a long
        // swipe deletes/selects/inserts many units instead of just one.
        val count = max(1, swipe.unitCount)
        return when (swipe.direction) {
            SpaceBarSwipeDirection.LEFT -> {
                val action = prefs.gestures.spaceBarSwipeLeft.get()
                when (action) {
                    SwipeAction.NO_ACTION -> false
                    SwipeAction.MOVE_CURSOR_LEFT -> true
                    else -> {
                        imeController.executeSwipeAction(action, count)
                        true
                    }
                }
            }
            SpaceBarSwipeDirection.RIGHT -> {
                val action = prefs.gestures.spaceBarSwipeRight.get()
                when (action) {
                    SwipeAction.NO_ACTION -> false
                    SwipeAction.MOVE_CURSOR_RIGHT -> true
                    else -> {
                        imeController.executeSwipeAction(action, count)
                        true
                    }
                }
            }
            SpaceBarSwipeDirection.UP -> {
                imeController.executeSwipeAction(prefs.gestures.spaceBarSwipeUp.get(), count)
                true
            }
            null -> false
        }
    }

    private suspend fun finishDeleteSwipe(swipe: DeleteSwipe) {
        if (swipe.unitCount > 0) {
            val selection = swipe.currentSelection ?: swipe.baseSelection
            if (selection != null) {
                if (swipe.action.commitsDeletion()) {
                    if (selection.end > selection.start) {
                        imeController.updateState { deleteSelectionFromGesture(selection) }
                    } else {
                        imeController.updateState {
                            updateSelectionFromGesture(EditorSelection(selection.start, selection.start))
                        }
                    }
                } else {
                    // SELECT_* modes intentionally leave the highlighted text selected.
                    imeController.updateState { updateSelectionFromGesture(selection) }
                }
            }
        } else {
            // Swiped back to the origin: restore the original selection and delete nothing.
            swipe.baseSelection?.let { base ->
                imeController.updateState { updateSelectionFromGesture(base) }
            }
        }
    }

    context(scope: CoroutineScope)
    suspend fun handleUp(up: PointerInputChange, size: IntSize) {
        trackedPeekPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            imeController.updateState {
                trackedPointer.peekKey?.attrs?.output?.let { emit(it) }
                if (trackedPointer.peekMustSwitchBack) {
                    if (trackedPointer.downKey.isShiftKey) {
                        revertCycleInputShiftState(
                            trackedPointer.downLayerId, trackedPointer.downInputShiftState
                        )
                    } else {
                        switchTouchLayer(trackedPointer.downLayerId)
                    }
                }
            }
            trackedPeekPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedOutputPointer.value?.takeIf { it.id == up.id }?.let { pointerAtUp ->
            // On fast swipes the final coordinates often arrive only with the UP event. Feed them
            // into the gesture calculation first, otherwise the last part of the travel is lost.
            if (!up.pressed) {
                if (activeDeleteSwipe != null) {
                    updateDeleteSwipe(up, force = true)
                } else if (activeSpaceBarSwipe != null) {
                    updateSpaceBarSwipe(pointerAtUp, up)
                }
            }
            val trackedPointer = trackedOutputPointer.value?.takeIf { it.id == up.id } ?: pointerAtUp
            trackedPointer.longPress.extendedJob?.cancel()
            trackedPointer.repeatJob?.cancel()
            val deleteSwipe = activeDeleteSwipe
            val spaceBarSwipe = activeSpaceBarSwipe
            if (deleteSwipe != null && deleteSwipe.active) {
                finishDeleteSwipe(deleteSwipe)
            } else if (spaceBarSwipe != null && spaceBarSwipe.direction != null) {
                if (!trackedPointer.directLongPressActionEmitted && !trackedPointer.repeatEmitted) {
                    val suppressSpace = finishSpaceBarSwipe(spaceBarSwipe)
                    if (!suppressSpace) {
                        imeController.updateState {
                            trackedPointer.downKey.attrs.output?.let { emit(it) }
                        }
                    }
                }
            } else if (!trackedPointer.directLongPressActionEmitted && !trackedPointer.repeatEmitted) {
                imeController.updateState {
                    val output = when {
                        trackedPointer.downKey == trackedMultiTapSeq.value?.touchKey -> null
                        trackedPointer.longPress.shouldShowExtendedPopup() -> {
                            trackedPointer.longPress.extendedKeys.getOrNull(trackedPointer.longPress.extendedFocusedIndex)
                                ?.data?.output
                        }
                        else -> trackedPointer.downKey.attrs.output
                    }
                    output?.let { emit(it) }
                }
            }
            trackedPeekPointer.update { it?.copy(peekMustSwitchBack = true) }
            activeDeleteSwipe = null
            activeSpaceBarSwipe = null
            trackedOutputPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedMultiTapSeq.update { multiTapSeq ->
            if (multiTapSeq != null) {
                multiTapSeq.commitJob?.cancel() // should never happen, but still ensure we never have 2 active jobs
                multiTapSeq.copy(
                    commitJob = scope.launch {
                        val timingOptions = interactionController.activeTimingOptions.value
                        delay(timingOptions.multiTapTimeout)
                        mutateLocked {
                            if (trackedOutputPointer.value?.longPress?.extendedBounds?.isEmpty == false) {
                                return@mutateLocked
                            }
                            multiTapSeq.commit()
                        }
                    }
                )
            } else null
        }
    }

    suspend fun cancelNonPresent(changes: List<PointerInputChange>) {
        trackedPeekPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelPeek()
            }
        }
        trackedOutputPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelOutput()
            }
        }
    }

    suspend fun cancelAll() {
        cancelPeek()
        cancelOutput()
    }

    private suspend fun cancelPeek() {
        val trackedPointer = trackedPeekPointer.getAndUpdate { null } ?: return
        imeController.updateState {
            if (trackedPointer.downKey.isShiftKey) {
                revertCycleInputShiftState(
                    trackedPointer.downLayerId, trackedPointer.downInputShiftState
                )
            } else {
                switchTouchLayer(trackedPointer.downLayerId)
            }
        }
    }

    private suspend fun cancelOutput() {
        cancelMultiTap()
        val trackedPointer = trackedOutputPointer.getAndUpdate { null }
        trackedPointer?.longPress?.extendedJob?.cancel()
        trackedPointer?.repeatJob?.cancel()
        val swipe = activeDeleteSwipe
        activeDeleteSwipe = null
        activeSpaceBarSwipe = null
        if (swipe != null && swipe.active) {
            imeController.updateState { updateSelectionFromGesture(swipe.baseSelection) }
        }
    }

    private suspend fun cancelMultiTap() {
        val multiTapSeq = trackedMultiTapSeq.getAndUpdate { null } ?: return
        multiTapSeq.commitJob?.cancel()
    }

    private fun Offset.normalized(size: IntSize): Offset {
        return Offset(x / size.width, y / size.height)
    }

    context(scope: CoroutineScope)
    private fun launchExtendedLongPressJob(
        longPressTimeout: Duration,
    ): Job = scope.launch(Dispatchers.Default) {
        delay(longPressTimeout)
        mutateLocked {
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commitJob?.cancel()
                trackedMultiTapSeq.value = null
            }
            val trackedPointer = trackedOutputPointer.value ?: return@mutateLocked
            val longPress = trackedPointer.longPress
            if (trackedPointer.downKey.isSpaceKey()) {
                val action = prefs.gestures.spaceBarLongPress.get()
                if (action == SwipeAction.NO_ACTION || action == SwipeAction.INSERT_SPACE) {
                    // Keep the normal space output on lift, just make sure no popup lingers.
                    trackedOutputPointer.value = trackedPointer.copy(longPress = LongPress.None)
                } else {
                    imeController.executeSwipeAction(action)
                    trackedOutputPointer.value = trackedPointer.copy(
                        longPress = LongPress.None,
                        directLongPressActionEmitted = true,
                    )
                }
                return@mutateLocked
            }
            val directAction = trackedPointer.downKey.directLongPressAction
            if (directAction != null) {
                imeController.updateState { emit(directAction) }
                trackedOutputPointer.value = trackedPointer.copy(
                    longPress = LongPress.None,
                    directLongPressActionEmitted = true,
                )
                return@mutateLocked
            }
            val anchorBounds = longPress.anchorBounds
            val extendedKeys = layoutExtendedLongPressKeys(longPress.extendedKeys, anchorBounds)
            val extendedBounds = anchorBounds.copy(
                left = extendedKeys.fastFold(anchorBounds.left) { acc, key -> minOf(acc, key.bounds.left) },
                top = extendedKeys.fastFold(anchorBounds.top) { acc, key -> minOf(acc, key.bounds.top) },
                right = extendedKeys.fastFold(anchorBounds.right) { acc, key -> maxOf(acc, key.bounds.right) },
                bottom = extendedKeys.fastFold(anchorBounds.bottom) { acc, key -> maxOf(acc, key.bounds.bottom) },
            )
            trackedOutputPointer.value = trackedPointer.copy(
                longPress = trackedPointer.longPress.copy(
                    simpleIndicateExtended = false,
                    extendedBounds = extendedBounds,
                    extendedKeys = extendedKeys,
                ),
            )
        }
    }
}

@Composable
fun rememberPointerTracker(
    touchKeyboard: TouchKeyboard,
): PointerTracker {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val interactionController = LocalInteractionController.current

    val showSimplePopups by prefs.keyboard.popupEnabled.collectAsState()
    val deleteSwipeAction by prefs.gestures.deleteKeySwipeLeft.collectAsState()
    val swipeDistanceThreshold by prefs.gestures.swipeDistanceThreshold.collectAsState()
    // TODO make configurable
    val peekDistanceSqMin = with(density) { 30.dp.toPx().pow(2) }
    val baseSwipeUnitPx = with(density) {
        swipeDistanceThreshold.coerceAtLeast(4).dp.toPx().fastCoerceAtLeast(1f)
    }
    // Fine-grained units let a single continuous swipe cover many characters/words instead of
    // capping out after one or two. The recognition threshold stays unchanged.
    val deleteSwipeUnitPx = (baseSwipeUnitPx / 8f).fastCoerceAtLeast(1f)
    val spaceBarSwipeUnitPx = (baseSwipeUnitPx / 12f).fastCoerceAtLeast(1f)
    val gestureSlopPx = (baseSwipeUnitPx * 0.5f).fastCoerceAtLeast(4f)
    val spaceBarSwipeThresholdPx = gestureSlopPx

    val pointerTracker = remember(
        touchKeyboard,
        showSimplePopups,
        deleteSwipeAction,
        deleteSwipeUnitPx,
        spaceBarSwipeUnitPx,
        spaceBarSwipeThresholdPx,
        gestureSlopPx,
    ) {
        PointerTracker(
            touchKeyboard = touchKeyboard,
            imeController = imeController,
            interactionController = interactionController,
            showSimplePopups = showSimplePopups,
            peekDistanceSqMin = peekDistanceSqMin,
            deleteSwipeAction = deleteSwipeAction,
            deleteSwipeUnitPx = deleteSwipeUnitPx,
            spaceBarSwipeUnitPx = spaceBarSwipeUnitPx,
            spaceBarSwipeThresholdPx = spaceBarSwipeThresholdPx,
            gestureSlopPx = gestureSlopPx,
        )
    }

    return pointerTracker
}

private data class QueuedPointerEvent(
    val event: PointerEvent,
    val size: IntSize,
)

fun Modifier.trackPointerInput(
    pointerTracker: PointerTracker,
    model: K3Model,
) = this.pointerInput(pointerTracker, model) {
    try {
        coroutineScope {
            // awaitPointerEventScope is a restricted coroutine scope: it may only call
            // suspending members of that scope. Queue events with the non-suspending trySend and
            // process them in one regular coroutine, avoiding both the restriction and the lag of
            // launching a new job for every pointer move.
            val events = Channel<QueuedPointerEvent>(Channel.UNLIMITED)
            launch {
                for (queued in events) {
                    processPointerEvent(pointerTracker, queued.event, queued.size)
                }
            }
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    events.trySend(QueuedPointerEvent(event, size))
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            pointerTracker.mutateLocked {
                cancelAll()
            }
        }
    }
}

context(scope: CoroutineScope)
private suspend fun processPointerEvent(
    pointerTracker: PointerTracker,
    event: PointerEvent,
    size: IntSize,
) {
    pointerTracker.mutateLocked {
        cancelNonPresent(event.changes)
    }
    event.changes.fastForEach { change ->
        pointerTracker.mutateLocked {
            when {
                change.changedToDown() -> handleDown(change, size)
                change.changedToUp() -> handleUp(change, size)
                !change.isConsumed -> handleMove(change, size)
            }
        }
        change.consume()
    }
}
