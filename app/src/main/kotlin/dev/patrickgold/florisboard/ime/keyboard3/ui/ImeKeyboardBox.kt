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

package dev.patrickgold.florisboard.ime.keyboard3.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.keyboard3.ImeIcons
import dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.hint.LongPressKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.interaction.LongPress
import dev.patrickgold.florisboard.ime.keyboard3.interaction.rememberPointerTracker
import dev.patrickgold.florisboard.ime.keyboard3.interaction.trackPointerInput
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchLayer
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModel
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.theme.ImeUiAttrs
import dev.patrickgold.florisboard.ime.theme.putAttr
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.toMm
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String

@Composable
fun ImeKeyboardBox(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val windowController = LocalWindowController.current

    val model by imeController.activeModel.collectAsState()
    val touchLayerId by imeController.activeTouchLayerId.collectAsState()
    val inputShiftState by imeController.activeInputShiftState.collectAsState()

    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val keyMarginHPx by remember {
        derivedStateOf { with(density) { windowSpec.keyMarginH.toPx() } }
    }
    val keyMarginVPx by remember {
        derivedStateOf { with(density) { windowSpec.keyMarginV.toPx() } }
    }

    val touchModelOptions by imeController.activeTouchModelOptions.collectAsState()
    var activeTouchModel by remember {
        mutableStateOf(imeController.touchModelCache.getFor(model, touchModelOptions) ?: TouchModel.Empty)
    }
    LaunchedEffect(model, touchModelOptions) {
        activeTouchModel = withContext(Dispatchers.Default) {
            imeController.touchModelCache.getOrComputeFor(model, touchModelOptions)
        }
    }

    val spaceBarMode by prefs.keyboard.spaceBarMode.collectAsState()
    val displayLanguageNamesIn by prefs.localization.displayLanguageNamesIn.collectAsState()
    // The compiled K3 model's locale comes from the layout XML (which is always "en" in the
    // foundation layouts), so it cannot be used for the spacebar language. Use the active subtype
    // instead, which is what the user actually selected in the language/subtype picker.
    val activeSubtype by FlorisApplication.getHack().subtypeManager.value.activeSubtypeFlow.collectAsState()
    val spaceBarDisplayOverride = remember(activeSubtype, spaceBarMode, displayLanguageNamesIn) {
        when (spaceBarMode) {
            SpaceBarMode.NOTHING -> K3String.Empty
            SpaceBarMode.CURRENT_LANGUAGE -> {
                val locale = activeSubtype.primaryLocale
                when (displayLanguageNamesIn) {
                    DisplayLanguageNamesIn.SYSTEM_LOCALE -> locale.displayName()
                    DisplayLanguageNamesIn.NATIVE_LOCALE -> locale.displayName(locale)
                }.asK3String()
            }
            SpaceBarMode.SPACE_BAR_KEY -> ImeIcons.SpaceBar
        }
    }

    val devtoolsEnabled by prefs.devtools.enabled.collectAsState()
    val debugShowTouchBoundaries by prefs.devtools.showKeyTouchBoundaries.collectAsState()

    BoxWithConstraints {
        val deviceWidthMm = remember(constraints.maxWidth) {
            with(density) { constraints.maxWidth.toDp().toMm().toInt() }
        }
        val activeTouchKeyboard = remember(activeTouchModel, deviceWidthMm) {
            activeTouchModel.selectKeyboard(deviceWidthMm)
        }
        // TODO this launched effect is a hack, there must be a better way
        LaunchedEffect(activeTouchKeyboard) {
            imeController.updateState {
                state = state.copy(effRowCount = activeTouchKeyboard.rowCount)
            }
        }
        val activeTouchLayer = remember(activeTouchKeyboard, touchLayerId) {
            if (touchLayerId == ImeLayerIds.Caps) {
                activeTouchKeyboard.layers[touchLayerId]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Shift]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Base]
            } else {
                activeTouchKeyboard.layers[touchLayerId]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Base]

            } ?: TouchLayer.Empty
        }

        val pointerTracker = rememberPointerTracker(activeTouchKeyboard)
        val trackedOutputPointer by pointerTracker.trackedOutputPointer.collectAsState()
        val trackedPeekPointer by pointerTracker.trackedPeekPointer.collectAsState()
        val trackedMultiTapSeq by pointerTracker.trackedMultiTapSeq.collectAsState()
        val glideTrailPoints by pointerTracker.glideTrailPoints.collectAsState()
        val glidePreview by pointerTracker.glidePreview.collectAsState()
        val showGlideTrail by prefs.glide.showTrail.collectAsState()
        val showGlidePreview by prefs.glide.showPreview.collectAsState()

        val keyboardRowHeightDp = FlorisImeSizing.keyboardRowBaseHeight
        val peekLineWidthPx = with(density) { 8.dp.toPx() }

        Box(
            modifier = modifier
                .layout { measurable, constraints ->
                    val effConstraints = Constraints.fixed(
                        width = constraints.maxWidth,
                        height = with(density) {
                            (keyboardRowHeightDp * activeTouchKeyboard.rowCount).roundToPx()
                        },
                    )
                    val placeable = measurable.measure(effConstraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .trackPointerInput(pointerTracker, model)
                .drawWithContent {
                    drawContent()
                    trackedPeekPointer?.let { trackedPeekPointer ->
                        val peekLine = trackedPeekPointer.peekLine
                        if (peekLine != null) {
                            drawLine(
                                color = Color.Red, // TODO customizable
                                start = peekLine.start,
                                end = peekLine.end,
                                strokeWidth = peekLineWidthPx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    if (showGlideTrail && glideTrailPoints.size >= 2) {
                        val trailColor = Color(0x88FFFFFF)
                        val trailWidth = with(density) { 6.dp.toPx() }
                        for (i in 1 until glideTrailPoints.size) {
                            drawLine(
                                color = trailColor,
                                start = glideTrailPoints[i - 1].position,
                                end = glideTrailPoints[i].position,
                                strokeWidth = trailWidth,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
        ) {
            for (touchKey in activeTouchLayer.keys) {
                if (touchKey.attrs.gap) {
                    continue
                }
                val touchKey by rememberUpdatedState(touchKey)
                val isPressed by remember {
                    derivedStateOf {
                        trackedOutputPointer?.downKey == touchKey ||
                            trackedPeekPointer?.let {
                                it.peekKey == touchKey || it.downKey.isShiftKey && touchKey.isShiftKey
                            } == true
                    }
                }
                val longPress by remember {
                    derivedStateOf {
                        trackedOutputPointer?.takeIf { it.downKey == touchKey }?.longPress ?: LongPress.None
                    }
                }
                val multiTapIndex by remember {
                    derivedStateOf {
                        trackedMultiTapSeq?.takeIf { it.touchKey == touchKey }?.multiTapIndex ?: -1
                    }
                }
                ImeKeyboardKeyBox(
                    touchKey = touchKey,
                    displayOverride = when {
                        touchKey.isSuitableForSpaceBarDisplayOverride -> spaceBarDisplayOverride
                        else -> null
                    },
                    isPressed = isPressed,
                    longPress = longPress,
                    multiTapIndex = multiTapIndex,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            // Quantize the left/right (and top/bottom) edges once, then derive the
                            // size from them. Rounding each key's width and origin independently
                            // made adjacent gaps alternate between 0 and 1 px, which looked like
                            // uneven spacing whenever the browser changed the keyboard width.
                            val left = (constraints.maxWidth * touchKey.bounds.left + keyMarginHPx).fastRoundToInt()
                            val right = (constraints.maxWidth * touchKey.bounds.right - keyMarginHPx).fastRoundToInt()
                            val top = (constraints.maxHeight * touchKey.bounds.top + keyMarginVPx).fastRoundToInt()
                            val bottom = (constraints.maxHeight * touchKey.bounds.bottom - keyMarginVPx).fastRoundToInt()
                            val effConstraints = Constraints.fixed(
                                width = (right - left).coerceAtLeast(1),
                                height = (bottom - top).coerceAtLeast(1),
                            )
                            val placeable = measurable.measure(effConstraints)
                            layout(placeable.width, placeable.height) { placeable.place(IntOffset(left, top)) }
                        },
                    inputShiftState = inputShiftState,
                )
                if (devtoolsEnabled && debugShowTouchBoundaries) {
                    Box(
                        modifier = Modifier
                            .layoutNormalized(touchKey.hitbox)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    color = Color.Red,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                    )
                }
            }
        }
        if (showGlidePreview && glidePreview.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = (-40).dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .background(color = Color(0xCC000000), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = glidePreview,
                        color = Color.White,
                        fontSize = 18.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImeKeyboardKeyBox(
    touchKey: TouchKey,
    displayOverride: K3StringOrDescriptor?,
    isPressed: Boolean,
    longPress: LongPress,
    multiTapIndex: Int,
    inputShiftState: InputShiftState,
    modifier: Modifier = Modifier,
) {
    val display = displayOverride ?: touchKey.display
    val output = touchKey.attrs.output
    val layerId = touchKey.attrs.layerId
    // Layer-switch labels (ABC / ?123 / =\<) use 18sp, matching FlorisBoard's own layer keys and
    // staying slightly below the 22sp letter labels. The spacebar label uses 12sp; the classical
    // theme rules target `code=32`, which K3 keys do not have, so without this code-level cap the
    // spacebar text renders at the full 22sp key size.
    val labelFontSize = remember(display, layerId, output) {
        when {
            layerId != null && display is K3String && display.toText().let {
                it.isNotEmpty() && it.length <= 4 && !it.contains('\n')
            } -> 18.sp
            layerId == null && output is K3String && output.toText() == " " -> 12.sp
            else -> null
        }
    }
    val attributes: SnyggQueryAttributes = remember(output, layerId, inputShiftState) {
        buildMap {
            if (output != null) {
                putAttr(ImeUiAttrs.Output, output)
            }
            if (layerId != null) {
                putAttr(ImeUiAttrs.TargetLayer, layerId)
            }
            putAttr(ImeUiAttrs.ShiftState, inputShiftState)
        }
    }
    // TODO some keys an be disabled (copy cut etc)
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE
    val multiTapKeys = touchKey.multiTapKeys

    SnyggBox(
        FlorisImeUi.Key.elementName,
        attributes = attributes,
        selector = selector,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .scaleToFitHorizontally(),
        ) {
            if (touchKey.shouldOverrideDisplayWithMultiTapKeys) {
                for ((index, multiTapKey) in multiTapKeys.withIndex()) {
                    Display3(
                        modifier = if (touchKey.shouldHighlightPendingMultiTapKey && index == multiTapIndex) {
                            Modifier.background(Color.Red) // TODO customizable
                        } else Modifier,
                        display = multiTapKey.display,
                        fontSize = labelFontSize,
                    )
                }
            } else {
                Display3(display, fontSize = labelFontSize)
            }
        }
        if (touchKey.longPressKeyHint != null) {
            // Keep edge-aligned hints (e.g. r=, t=[, c=', v=:) away from the keycap edge so their
            // side bearings do not make the gap to the next key look smaller than it really is.
            val hintPadding = when (touchKey.longPressKeyHintPlacement) {
                LongPressKeyHintPlacement.TOP_END, LongPressKeyHintPlacement.BOTTOM_END -> {
                    Modifier.padding(end = 2.dp)
                }
                LongPressKeyHintPlacement.TOP_START, LongPressKeyHintPlacement.BOTTOM_START -> {
                    Modifier.padding(start = 2.dp)
                }
                else -> Modifier
            }
            Display3(
                elementName = FlorisImeUi.KeyHint.elementName,
                attributes = attributes,
                modifier = Modifier
                    .align(touchKey.longPressKeyHintPlacement.alignment)
                    .then(hintPadding)
                    .scaleToFitHorizontally(),
                display = touchKey.longPressKeyHint,
            )
        }
        // TODO flick hints
    }
    LongPressBox(
        longPress = longPress,
        longPressSimpleDisplayOverride = multiTapKeys.getOrNull(multiTapIndex)?.display,
        attributes = attributes,
    )
}
