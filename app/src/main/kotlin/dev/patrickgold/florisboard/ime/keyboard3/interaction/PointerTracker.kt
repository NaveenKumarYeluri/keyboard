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
        val deleteSwipe: DeleteSwipe? = null,
        val spaceBarSwipe: SpaceBarSwipe? = null,
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
    val baseSelection: EditorSelection? = null,
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
                deleteSwipe = if (isDeleteSwipeKey) {
                    DeleteSwipe(
                        start = down.position,
                        action = deleteSwipeAction,
                        unitPx = deleteSwipeUnitPx,
                    )
                } else null,
                spaceBarSwipe = if (isSpaceBarKey) {
                    SpaceBarSwipe(
                        start = down.position,
                        unitPx = spaceBarSwipeUnitPx,
                        thresholdPx = spaceBarSwipeThresholdPx,
                    )
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
        trackedOutputPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            if (trackedPointer.deleteSwipe != null) {
                updateDeleteSwipe(trackedPointer, move)
                return@let
            }
            if (trackedPointer.spaceBarSwipe != null) {
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
        trackedPointer: TrackedPointer.Output,
        move: PointerInputChange,
    ) {
        val swipe = trackedPointer.deleteSwipe ?: return
        val distancePx = max(0f, swipe.start.x - move.position.x)
        val unitCount = max(0, floor(distancePx / swipe.unitPx).toInt() - 1)
        val editor = imeController.snapshotState().editor
        val baseSelection = swipe.baseSelection ?: editor.getSelectionRange() ?: return
        val unit = if (swipe.action.usesWordUnits()) OperationUnit.WORDS else OperationUnit.CHARACTERS
        val targetSelection = editor.selectionBefore(unitCount, unit, baseSelection) ?: return

        if (unitCount != swipe.unitCount) {
            interactionController.performFeedback(InteractionKind.GestureSwipe)
        }
        imeController.updateState {
            updateSelectionFromGesture(targetSelection)
        }

        var newPointer = trackedPointer.copy(
            deleteSwipe = swipe.copy(
                baseSelection = baseSelection,
                currentSelection = targetSelection,
                unitCount = unitCount,
                active = swipe.active || unitCount > 0,
            ),
        )
        if (unitCount > 0 && !swipe.active) {
            newPointer.longPress.extendedJob?.cancel()
            newPointer.repeatJob?.cancel()
            newPointer = newPointer.copy(longPress = LongPress.None)
        }
        trackedOutputPointer.value = newPointer
    }

    private suspend fun updateSpaceBarSwipe(
        trackedPointer: TrackedPointer.Output,
        move: PointerInputChange,
    ) {
        if (trackedPointer.directLongPressActionEmitted || trackedPointer.repeatEmitted) return
        val swipe = trackedPointer.spaceBarSwipe ?: return
        val dx = move.position.x - swipe.start.x
        val dy = move.position.y - swipe.start.y
        val absX = abs(dx)
        val absY = abs(dy)
        if (absX < swipe.thresholdPx && absY < swipe.thresholdPx) return

        val direction = when {
            absX >= absY -> if (dx < 0f) SpaceBarSwipeDirection.LEFT else SpaceBarSwipeDirection.RIGHT
            dy < 0f -> SpaceBarSwipeDirection.UP
            // Downward spacebar swipes are intentionally ignored.
            else -> return
        }
        val action = when (direction) {
            SpaceBarSwipeDirection.LEFT -> prefs.gestures.spaceBarSwipeLeft.get()
            SpaceBarSwipeDirection.RIGHT -> prefs.gestures.spaceBarSwipeRight.get()
            SpaceBarSwipeDirection.UP -> prefs.gestures.spaceBarSwipeUp.get()
        }
        val isCursorMovement =
            direction == SpaceBarSwipeDirection.LEFT && action == SwipeAction.MOVE_CURSOR_LEFT ||
                direction == SpaceBarSwipeDirection.RIGHT && action == SwipeAction.MOVE_CURSOR_RIGHT
        val isActive = direction == SpaceBarSwipeDirection.UP || action != SwipeAction.NO_ACTION
        var newSwipe = swipe.copy(
            direction = direction,
            active = swipe.active || isActive,
        )
        if (isCursorMovement) {
            val unitCount = max(0, floor(absX / swipe.unitPx).toInt())
            val delta = unitCount - swipe.executedCount
            if (delta > 0) {
                imeController.executeSwipeAction(action, delta)
            } else if (delta < 0) {
                val reverseAction = if (action == SwipeAction.MOVE_CURSOR_LEFT) {
                    SwipeAction.MOVE_CURSOR_RIGHT
                } else {
                    SwipeAction.MOVE_CURSOR_LEFT
                }
                imeController.executeSwipeAction(reverseAction, -delta)
            }
            newSwipe = newSwipe.copy(unitCount = unitCount, executedCount = unitCount)
        }
        // Once a swipe is recognized, neither the long-press nor the repeat action may fire anymore.
        trackedPointer.longPress.extendedJob?.cancel()
        trackedPointer.repeatJob?.cancel()
        trackedOutputPointer.value = trackedPointer.copy(
            longPress = LongPress.None,
            repeatJob = null,
            spaceBarSwipe = newSwipe,
        )
    }

    /**
     * Finishes a spacebar swipe and returns whether the normal space output must be suppressed.
     */
    private suspend fun finishSpaceBarSwipe(swipe: SpaceBarSwipe): Boolean {
        return when (swipe.direction) {
            SpaceBarSwipeDirection.LEFT -> {
                val action = prefs.gestures.spaceBarSwipeLeft.get()
                when (action) {
                    SwipeAction.NO_ACTION -> false
                    SwipeAction.MOVE_CURSOR_LEFT -> true
                    else -> {
                        imeController.executeSwipeAction(action)
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
                        imeController.executeSwipeAction(action)
                        true
                    }
                }
            }
            SpaceBarSwipeDirection.UP -> {
                imeController.executeSwipeAction(prefs.gestures.spaceBarSwipeUp.get())
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
        trackedOutputPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            trackedPointer.longPress.extendedJob?.cancel()
            trackedPointer.repeatJob?.cancel()
            val deleteSwipe = trackedPointer.deleteSwipe
            val spaceBarSwipe = trackedPointer.spaceBarSwipe
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
        val trackedPointer = trackedOutputPointer.getAndUpdate { null } ?: return
        trackedPointer.longPress.extendedJob?.cancel()
        trackedPointer.repeatJob?.cancel()
        val swipe = trackedPointer.deleteSwipe
        if (swipe != null && swipe.active) {
            swipe.baseSelection?.let { base ->
                imeController.updateState { updateSelectionFromGesture(base) }
            }
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
    val deleteSwipeUnitPx = with(density) {
        (swipeDistanceThreshold.coerceAtLeast(4).dp.toPx() / 4f).fastCoerceAtLeast(1f)
    }
    val spaceBarSwipeUnitPx = with(density) {
        (swipeDistanceThreshold.coerceAtLeast(4).dp.toPx() / 2f).fastCoerceAtLeast(1f)
    }
    val spaceBarSwipeThresholdPx = with(density) {
        swipeDistanceThreshold.coerceAtLeast(4).dp.toPx().fastCoerceAtLeast(1f)
    }

    val pointerTracker = remember(
        touchKeyboard,
        showSimplePopups,
        deleteSwipeAction,
        deleteSwipeUnitPx,
        spaceBarSwipeUnitPx,
        spaceBarSwipeThresholdPx,
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
        )
    }

    return pointerTracker
}

fun Modifier.trackPointerInput(
    pointerTracker: PointerTracker,
    model: K3Model,
) = this.pointerInput(pointerTracker, model) {
    try {
        coroutineScope {
            val scope = this
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val sizeAtEvent = size
                    scope.launch {
                        pointerTracker.mutateLocked {
                            cancelNonPresent(event.changes)
                        }
                    }
                    event.changes.fastForEach { change ->
                        if (change.changedToDown()) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleDown(change, sizeAtEvent)
                                }
                            }
                        } else if (change.changedToUp()) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleUp(change, sizeAtEvent)
                                }
                            }
                        } else if (!change.isConsumed) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleMove(change, sizeAtEvent)
                                }
                            }
                        }
                        change.consume()
                    }
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
