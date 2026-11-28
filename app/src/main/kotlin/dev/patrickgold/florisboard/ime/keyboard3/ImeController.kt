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

package dev.patrickgold.florisboard.ime.keyboard3

import android.icu.text.BreakIterator
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.staticCompositionLocalOf
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.io.StorageController
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard3.extension.loadFoundationKeyboard
import dev.patrickgold.florisboard.ime.keyboard3.hint.FlickKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.hint.LongPressKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.touch.FnKeyArrangement
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.ShiftKeyBehavior
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelCache
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.nlp.BreakIterators
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.lowercase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.florisboard.lib.kotlin.collectIn
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.lib.text.buildK3String
import org.k3lp.lib.text.normalize
import org.k3lp.lib.text.unicode.NormalizationForm
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3InputMethod
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Provides the [ImeController] instance this composition tree is associated with.
 */
val LocalImeController = staticCompositionLocalOf<ImeController> {
    error("No IME controller is associated with this composition tree.")
}

private data class SuggestionRequest(
    val subtype: Subtype,
    val content: K3Content,
) {
    /** Content equality on every keystroke is not reliable enough; key off the actual text. */
    val key: String
        get() = buildString {
            append(subtype.id).append('\u0000')
            val surrounding = content.surroundingText
            append(surrounding.textBefore).append('\u0001')
            append(surrounding.textSelected).append('\u0002')
            append(surrounding.textAfter).append('\u0003')
            append(content.compositionText ?: "").append('\u0004')
            append(content.selection)
        }
}

private const val SUGGESTION_DEBOUNCE_MS = 60L

@OptIn(ExperimentalAtomicApi::class)
class ImeController(
    storageController: StorageController,
    val clipboardManager: ClipboardManager, // TODO ClipboardController
    initialState: ImeState = ImeState(),
    val touchModelCache: TouchModelCache = TouchModelCache(),
) : K3InputMethod<ImeState, ImeEditor, ImeController.UpdateImeStateScope>(
    initialState = initialState,
) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val breakIterators = BreakIterators()
    private val expectedContentQueue = ExpectedContentQueue()
    private val emitInterceptor = AtomicReference<EmitInterceptor?>(null)
    private val nlpManager by lazy { FlorisApplication.getHack().nlpManager.value }

    /**
     * The active K3 layout ID. Telugu phonetic mode needs a raw Latin buffer because K3 transforms
     * cannot convert trailing consonants reliably.
     */
    @Volatile
    private var activeLayoutId = ""

    @Volatile
    private var phoneticLatin = ""

    @Volatile
    private var phoneticOutput = ""

    /**
     * Number of code points to delete on the next backspace right after a committed glide, when the
     * "always delete word" preference is enabled. Zero means the shortcut is not armed.
     */
    @Volatile
    private var glideBackspaceWordLength = 0

    /**
     * SubtypeManager reads the preference datastore when it is constructed, so it must only be
     * created after [FlorisApplication.preferenceStoreLoaded] becomes true. Creating it eagerly
     * here crashed Application.onCreate before the datastore was ready.
     */
    private val popupsEnabledFlow = FlorisApplication.getHack().preferenceStoreLoaded
        .flatMapLatest { loaded ->
            if (loaded) {
                FlorisApplication.getHack().subtypeManager.value.activeSubtypeFlow
                    .map { it.popupMapping != "none" }
            } else {
                flowOf(true)
            }
        }

    val activeTouchModelOptions = combine<Any, TouchModelOptions>(
        prefs.keyboard.numberRow.asFlow(),
        prefs.keyboard.fnKeyEnabled.asFlow(),
        prefs.keyboard.fnKeyArrangement.asFlow(),
        prefs.keyboard.longPressKeyHintEnabled.asFlow(),
        prefs.keyboard.longPressKeyHintPlacement.asFlow(),
        prefs.keyboard.multiTapHighlightEnabled.asFlow(),
        prefs.keyboard.flickKeyHintEnabled.asFlow(),
        prefs.keyboard.flickKeyHintPlacement.asFlow(),
        popupsEnabledFlow,
    ) { values ->
        TouchModelOptions(
            showNumberRow = values[0] as Boolean,
            fnKeyEnabled = values[1] as Boolean,
            fnKeyArrangement = values[2] as FnKeyArrangement,
            longPressKeyHintEnabled = values[3] as Boolean,
            longPressKeyHintPlacement = values[4] as LongPressKeyHintPlacement,
            multiTapHighlightEnabled = values[5] as Boolean,
            flickKeyHintEnabled = values[6] as Boolean,
            flickKeyHintPlacement = values[7] as FlickKeyHintPlacement,
            popupsEnabled = values[8] as Boolean,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TouchModelOptions.Default)

    // Key and icon composables only need these narrow values. Distinct flows keep content-only
    // state updates (one per keystroke) from recomposing every key on the keyboard.
    val activeModel = activeState.map { it.model }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, initialState.model)
    val activeTouchLayerId = activeState.map { it.touchLayerId }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, initialState.touchLayerId)
    val activeInputShiftState = activeState.map { it.flags.inputShiftState }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, initialState.flags.inputShiftState)
    val activeEditorInfo = activeState.map { it.editor.info }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, initialState.editor.info)
    val activeDebugShowDragAndDropHelpers = activeState.map { it.flags.debugShowDragAndDropHelpers }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, initialState.flags.debugShowDragAndDropHelpers)

    // TODO check if we can implement this differently
    val activeSmartbarVisibleDynamicActionsCount = MutableStateFlow(0)

    init {
        combine(
            prefs.devtools.enabled.asFlow(),
            prefs.devtools.showDragAndDropHelpers.asFlow(),
        ) { devtoolsEnabled, showDragAndDropHelpers ->
            devtoolsEnabled && showDragAndDropHelpers
        }.collectIn(scope) { showDragAndDropHelpers ->
            updateState {
                state = state.copy(
                    flags = state.flags
                        .withDebugShowDragAndDropHelpers(showDragAndDropHelpers),
                )
            }
        }

        // TODO proper stateful compilation
        scope.launch {
            // Reload the keyboard layout whenever the active subtype changes, so the layout and
            // spacebar language match the subtype selected in the settings. Wait for the preference
            // datastore before touching SubtypeManager.
            FlorisApplication.getHack().preferenceStoreLoaded
                .filter { it }
                .flatMapLatest { FlorisApplication.getHack().subtypeManager.value.activeSubtypeFlow }
                .collect { subtype ->
                    loadFoundationKeyboard(this@ImeController, storageController.activeStorage.value, subtype)
                }
        }

        // Suggestions used to re-run for every state emission (cursor anchors, shift changes, popup
        // toggles) and every keystroke triggered an offline dictionary scan. Only rerun when the
        // editor text or active subtype actually changes, and coalesce bursts of keystrokes.
        scope.launch {
            FlorisApplication.getHack().preferenceStoreLoaded
                .filter { it }
                .flatMapLatest {
                    combine(
                        activeState.map { state -> contentForSuggestions(state) },
                        FlorisApplication.getHack().subtypeManager.value.activeSubtypeFlow,
                    ) { content, subtype -> SuggestionRequest(subtype, content) }
                }
                .distinctUntilChangedBy { it.key }
                .debounce(SUGGESTION_DEBOUNCE_MS)
                .flatMapLatest { request ->
                    // Explicit Unit type: this flow is only used to run the suggestion call in the
                    // flatMapLatest coroutine, so it intentionally emits no values.
                    flow<Unit> {
                        runCatching {
                            if (nlpManager.isSuggestionOn()) {
                                nlpManager.suggest(request.subtype, request.content)
                            }
                        }.onFailure { flogDebug { "Suggestion pipeline failed: $it" } }
                    }
                }
                .collect { }
        }
    }

    /**
     * Returns true if the given hardware key event will be handled (and thus consumed) by this input method.
     * Handled actions are performed here (and not on key up) so that hardware key auto-repeat works.
     */
    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                updateStateBlocking {
                    emitBackspace()
                }
                true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                updateStateBlocking {
                    emitForwardDelete()
                }
                true
            }
            KeyEvent.KEYCODE_SPACE -> {
                updateStateBlocking {
                    emitText(SPACE_SEQ)
                }
                true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                updateStateBlocking {
                    emitEnter()
                }
                true
            }
            else -> {
                // Printable key: commit the character derived from the key and its meta state, so that hardware
                // keyboard input flows through the same pipeline as on-screen key input and suggestions are
                // updated accordingly.
                if (event.isPrintingKey) {
                    val unicodeChar = event.getUnicodeChar(event.metaState)
                    if (unicodeChar != 0) {
                        updateStateBlocking {
                            emitText(String(Character.toChars(unicodeChar)).asK3String())
                        }
                        true
                    } else {
                        // Dead key or unsupported character: let the system handle it.
                        false
                    }
                } else {
                    false
                }
            }
        }
    }

    fun onHardwareKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        // All actions are performed on key down to support auto-repeat; key up events of keys consumed in
        // [onHardwareKeyDown] are consumed without further action.
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> true
            else -> event.isPrintingKey && event.getUnicodeChar(event.metaState) != 0
        }
    }

    fun setEmitInterceptor(interceptor: EmitInterceptor?) {
        emitInterceptor.store(interceptor)
    }

    /**
     * Called by the layout loader before the new model is switched in. Clears any in-progress
     * phonetic word so its buffer cannot leak into a different layout.
     */
    fun setActiveLayoutId(layoutId: String) {
        activeLayoutId = layoutId
        glideBackspaceWordLength = 0
        clearPhoneticBuffer()
        // Switching layouts can happen after the editor info was applied. Keep composing off for
        // Telugu phonetic so its word replacements never happen inside a K3 composing region.
        state = state.copy(
            flags = state.flags.withComposingEnabled(
                shouldComposeForEditor(
                    state.editor.info,
                    state.touchLayerId,
                    state.flags.keyVariation,
                ),
            ),
        )
    }

    private fun clearPhoneticBuffer() {
        phoneticLatin = ""
        phoneticOutput = ""
    }

    /**
     * Gecko web text fields and Joplin's note editor frequently leave composing regions and cursor
     * updates out of sync with the JavaScript editor. That can surface as stray characters, vanished
     * words on space, or text that cannot be deleted. The offline suggestion pipeline already
     * supports non-composing editors, so disable composing for these editors and drive suggestions
     * from surrounding text instead.
     */
    private fun shouldDisableComposingForEditor(info: FlorisEditorInfo): Boolean {
        val packageName = info.packageName.orEmpty()
        return packageName.startsWith("org.mozilla.") ||
            packageName.contains("fennec") ||
            packageName.startsWith("net.cozic.joplin") ||
            packageName.contains("joplin")
    }

    /**
     * Telugu phonetic text is committed natively one step at a time. A K3 composing region would
     * span the completed word and make the next transliteration replace or merge into it.
     */
    private fun shouldComposeForEditor(
        info: FlorisEditorInfo,
        touchLayerId: K3LayerId,
        keyVariation: KeyVariation,
    ): Boolean {
        if (activeLayoutId == "telugu-phonetic") return false
        return when (touchLayerId) {
            ImeLayerIds.Numpad, ImeLayerIds.Telpad -> false
            else -> keyVariation != KeyVariation.PASSWORD &&
                info.isRichInputEditor &&
                info.inputAttributes.flagTextAutoCorrect &&
                !shouldDisableComposingForEditor(info)
        }
    }

    /**
     * While typing in Telugu phonetic mode the editor contains transliterated Telugu text, but
     * suggestions match better against the raw Latin word the user actually typed. Feed that raw
     * word to the NLP pipeline without changing the editor state.
     */
    private fun contentForSuggestions(source: ImeState): K3Content {
        if (activeLayoutId != "telugu-phonetic" || phoneticLatin.isEmpty()) {
            return source.content
        }
        val raw = phoneticLatin
        val surrounding = source.content.surroundingText
        val before = surrounding.textBefore
        val contextPrefix = if (phoneticOutput.isNotEmpty() && before.endsWith(phoneticOutput)) {
            before.dropLast(phoneticOutput.length)
        } else {
            before.removeSuffix(before.takeLastWhile { it.isLetterOrDigit() || it == '\'' })
        }
        val syntheticBefore = contextPrefix + raw
        return source.content.copy(
            selection = K3TextRange(syntheticBefore.length),
            composition = null,
            surroundingText = surrounding.copy(
                textBefore = syntheticBefore,
                textSelected = "",
                textAfter = surrounding.textAfter,
            ),
            inputContext = syntheticBefore.asK3String(),
        )
    }

    fun snapshotState(): ImeState = activeState.value

    /**
     * Commits a completed glide by resolving the visited-key sequence against the offline
     * dictionaries/aliases. A glide never emits partial text, so the result is inserted as a new
     * word instead of replacing the current composing word.
     */
    suspend fun commitGlide(sequence: String, alternateSequence: String? = null) {
        val subtype = FlorisApplication.getHack().subtypeManager.value.activeSubtype
        val normalized = sequence.lowercase(subtype.primaryLocale)
        if (normalized.codePointCount(0, normalized.length) < 2) return
        val alternate = alternateSequence?.lowercase(subtype.primaryLocale)?.takeIf {
            it != normalized && it.codePointCount(0, it.length) >= 2
        }
        val resolved = withContext(Dispatchers.Default) {
            val primary = nlpManager.glideCandidates(subtype, normalized, 3).firstOrNull()
            if (alternate == null) {
                primary
            } else {
                val alternateCandidate = nlpManager.glideCandidates(subtype, alternate, 3).firstOrNull()
                chooseGlideCandidate(primary, normalized, alternateCandidate, alternate)
            }
        }?.text?.toString()
        // A glide that started on a shifted letter must not be forced back to lowercase.
        val capitalize = sequence.firstOrNull()?.isUpperCase() == true
        fun applyGlideCase(text: String): String {
            return if (capitalize) text.replaceFirstChar { it.uppercaseChar() } else text
        }
        var committed = ""
        updateState {
            committed = if (resolved != null) {
                emitGlideText(applyGlideCase(resolved), transliterate = false)
            } else {
                emitGlideText(
                    applyGlideCase(normalized),
                    transliterate = activeLayoutId == "telugu-phonetic",
                )
            }
        }
        glideBackspaceWordLength = if (
            committed.isNotEmpty() && prefs.glide.immediateBackspaceDeletesWord.get()
        ) {
            committed.codePointCount(0, committed.length)
        } else {
            0
        }
    }

    /**
     * Picks the better offline match between the turn-based glide path and the full crossed-key path.
     * Confidence decides normally; on a tie the longer exact match wins because straight segments
     * can legitimately contain letters the turn decoder misses.
     */
    private fun chooseGlideCandidate(
        primary: SuggestionCandidate?,
        primarySequence: String,
        alternate: SuggestionCandidate?,
        alternateSequence: String,
    ): SuggestionCandidate? {
        if (primary == null) return alternate
        if (alternate == null) return primary
        if (alternate.confidence > primary.confidence + 0.01) return alternate
        if (primary.confidence > alternate.confidence + 0.01) return primary
        val alternateExact = alternate.text.toString().equals(alternateSequence, ignoreCase = true)
        return if (alternateExact && alternateSequence.codePointCount(0, alternateSequence.length) >
            primarySequence.codePointCount(0, primarySequence.length)
        ) {
            alternate
        } else {
            primary
        }
    }

    /**
     * Executes a general [SwipeAction] triggered by a gesture, such as a spacebar swipe or long-press.
     *
     * @param action The action to execute.
     * @param count How many units (characters, words, cursor steps) the action should apply.
     */
    suspend fun executeSwipeAction(action: SwipeAction, count: Int = 1) {
        glideBackspaceWordLength = 0
        if (count <= 0) return
        when (action) {
            SwipeAction.NO_ACTION -> Unit
            SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE -> updateState { cycleImeUiMode(-1) }
            SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE -> updateState { cycleImeUiMode(1) }
            SwipeAction.DELETE_CHARACTER -> updateState {
                repeat(count) { emitBackspace() }
            }
            SwipeAction.DELETE_CHARACTERS_PRECISELY -> updateState {
                deletePrecisely(count, OperationUnit.CHARACTERS)
            }
            SwipeAction.DELETE_WORD -> updateState {
                deletePrecisely(count, OperationUnit.WORDS)
            }
            SwipeAction.DELETE_WORDS_PRECISELY -> updateState {
                deletePrecisely(count, OperationUnit.WORDS)
            }
            SwipeAction.HIDE_KEYBOARD -> FlorisImeService.hideUi()
            SwipeAction.INSERT_SPACE -> updateState {
                repeat(count) { emitText(SPACE_SEQ) }
            }
            SwipeAction.MOVE_CURSOR_UP -> moveCursor(KeyEvent.KEYCODE_DPAD_UP, count)
            SwipeAction.MOVE_CURSOR_DOWN -> moveCursor(KeyEvent.KEYCODE_DPAD_DOWN, count)
            SwipeAction.MOVE_CURSOR_LEFT -> moveCursor(KeyEvent.KEYCODE_DPAD_LEFT, count)
            SwipeAction.MOVE_CURSOR_RIGHT -> moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT, count)
            SwipeAction.MOVE_CURSOR_START_OF_LINE -> moveCursor(KeyEvent.KEYCODE_MOVE_HOME)
            SwipeAction.MOVE_CURSOR_END_OF_LINE -> moveCursor(KeyEvent.KEYCODE_MOVE_END)
            SwipeAction.MOVE_CURSOR_START_OF_PAGE -> moveCursor(KeyEvent.KEYCODE_MOVE_HOME, ctrl = true)
            SwipeAction.MOVE_CURSOR_END_OF_PAGE -> moveCursor(KeyEvent.KEYCODE_MOVE_END, ctrl = true)
            SwipeAction.REDO -> updateState { state.editor.performRedo() }
            SwipeAction.SELECT_CHARACTERS_PRECISELY -> updateState {
                selectPrecisely(count, OperationUnit.CHARACTERS)
            }
            SwipeAction.SELECT_WORDS_PRECISELY -> updateState {
                selectPrecisely(count, OperationUnit.WORDS)
            }
            SwipeAction.SHIFT -> updateState { cycleInputShiftState(false) }
            SwipeAction.SHOW_INPUT_METHOD_PICKER -> FlorisImeService.showImePicker()
            SwipeAction.SHOW_SUBTYPE_PICKER -> updateState { emit(ImeActions.ShowSubtypePicker) }
            SwipeAction.SWITCH_TO_PREV_SUBTYPE -> FlorisApplication.getHack().subtypeManager.value.switchToPrevSubtype()
            SwipeAction.SWITCH_TO_NEXT_SUBTYPE -> FlorisApplication.getHack().subtypeManager.value.switchToNextSubtype()
            SwipeAction.SWITCH_TO_CLIPBOARD_CONTEXT -> updateState { setImeUiMode(ImeUiMode.CLIPBOARD) }
            SwipeAction.SWITCH_TO_MEDIA_CONTEXT -> updateState { setImeUiMode(ImeUiMode.MEDIA) }
            SwipeAction.SWITCH_TO_PREV_KEYBOARD -> FlorisImeService.switchToPrevInputMethod()
            SwipeAction.TOGGLE_COMPACT_LAYOUT -> FlorisImeService.windowControllerOrNull()?.actions?.toggleCompactLayout()
            SwipeAction.TOGGLE_SMARTBAR_VISIBILITY -> scope.launch {
                prefs.smartbar.enabled.set(!prefs.smartbar.enabled.get())
            }
            SwipeAction.UNDO -> updateState { state.editor.performUndo() }
        }
    }

    private suspend fun moveCursor(keyCode: Int, count: Int = 1, ctrl: Boolean = false) {
        val metaState = if (ctrl) {
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        } else {
            0
        }
        updateState {
            repeat(count) {
                state.editor.sendDownUpKeyEvent(keyCode, metaState)
            }
        }
    }

    // TODO evaluate if we can move to a clean coroutine-based approach in FlorisBoard
    //  for now this helper can be used to update the state from non-suspending contexts
    inline fun updateStateBlocking(crossinline function: UpdateImeStateScope.() -> Unit) {
        runBlocking {
            updateState(function)
        }
    }

    override fun updateStateScopeOf(state: ImeState): UpdateImeStateScope {
        return UpdateImeStateScope(state)
    }

    inner class UpdateImeStateScope(
        state: ImeState,
    ) : UpdateStateScope<ImeState, ImeEditor>(state) {
        fun handleStartInputView(
            ic: WeakReference<InputConnection>,
            info: FlorisEditorInfo,
        ) {
            val newTouchLayerId: K3LayerId
            val keyVariation: KeyVariation
            when (info.inputAttributes.type) {
                InputAttributes.Type.NUMBER -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Numpad
                }
                InputAttributes.Type.PHONE -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Telpad
                }
                InputAttributes.Type.TEXT -> {
                    keyVariation = when (info.inputAttributes.variation) {
                        InputAttributes.Variation.EMAIL_ADDRESS,
                        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                            -> {
                            KeyVariation.EMAIL_ADDRESS
                        }
                        InputAttributes.Variation.PASSWORD,
                        InputAttributes.Variation.VISIBLE_PASSWORD,
                        InputAttributes.Variation.WEB_PASSWORD,
                            -> {
                            KeyVariation.PASSWORD
                        }
                        InputAttributes.Variation.URI -> {
                            KeyVariation.URI
                        }
                        else -> {
                            KeyVariation.NORMAL
                        }
                    }
                    newTouchLayerId = ImeLayerIds.Base
                }
                else -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Base
                }
            }
            val initialSelection = info.initialSelection
            val initialSurrounding = K3SurroundingText(
                textBefore = info.getInitialTextBeforeCursor(20)?.toString() ?: "",
                textSelected = info.getInitialSelectedText()?.toString() ?: "",
                textAfter = info.getInitialTextAfterCursor(20)?.toString() ?: "",
            )

            val rememberCapsLockState = prefs.typing.rememberCapsLockState.get() &&
                state.flags.inputShiftState == InputShiftState.CAPS_LOCK

            state = state.copy(
                editor = ImeEditor(ic, info),
                touchLayerId = when {
                    rememberCapsLockState && newTouchLayerId.isTextLayer() -> ImeLayerIds.Caps
                    else -> newTouchLayerId
                },
                flags = state.flags
                    .withKeyVariation(keyVariation)
                    .withImeUiMode(
                        if (state.flags.imeUiMode != ImeUiMode.CLIPBOARD || prefs.clipboard.historyHideOnNextTextField.get()) {
                            ImeUiMode.TEXT
                        } else {
                            state.flags.imeUiMode
                        }
                    )
                    .withActionsOverflowVisible(false)
                    .withActionsEditorVisible(false)
                    .withInputShiftState(
                        when {
                            rememberCapsLockState -> InputShiftState.CAPS_LOCK
                            prefs.typing.autoCapitalization.get() &&
                                info.initialCapsMode != InputAttributes.CapsMode.NONE -> {
                                InputShiftState.SHIFTED_AUTOMATIC
                            }
                            else -> InputShiftState.UNSHIFTED
                        }
                    )
                    .withComposingEnabled(
                        // TODO review if this is the correct approach for composing region support detection
                        //  important: for codemirror6 in browsers and Gecko web text fields, composing
                        //  must be disabled, else stray/stuck characters start to appear.
                        shouldComposeForEditor(info, newTouchLayerId, keyVariation)
                    )
                    .withIncognitoMode(
                        when (prefs.suggestion.incognitoMode.get()) {
                            IncognitoMode.FORCE_OFF -> false
                            IncognitoMode.FORCE_ON -> true
                            IncognitoMode.DYNAMIC_ON_OFF -> {
                                info.imeOptions.flagNoPersonalizedLearning ||
                                    prefs.suggestion.forceIncognitoModeFromDynamic.get()
                            }
                        }
                    )
            )
            resetContent(initialSelection, initialSurrounding)
            expectedContentQueue.clear()
        }

        fun handleUpdateSelection(newSelection: K3TextRange) {
            val expected = expectedContentQueue.popUntilOrNull { it == newSelection }
            if (expected != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            resetContent(newSelection, state.editor.getSurroundingText(50, 10))
            expectedContentQueue.push(state.content.selection)
        }

        /**
         * Applies a swipe-delete selection to the native editor. During a live swipe we only push
         * the expected selection into the dedup queue and skip [resetContent]; the expensive K3
         * content rebuild is done once when the gesture finishes. This keeps long swipes smooth.
         */
        fun updateSelectionFromGesture(selection: EditorSelection, syncState: Boolean = true) {
            val start = selection.start.coerceAtLeast(0)
            val end = selection.end.coerceAtLeast(start)
            val safeSelection = EditorSelection(start, end)
            val newSelection = K3TextRange(start, end)
            if (syncState) {
                // Keep the original ordering so resetContent sees the new native selection.
                state.editor.setSelectionRange(safeSelection)
                resetContent(newSelection, state.editor.getSurroundingText(50, 10))
                expectedContentQueue.push(state.content.selection)
            } else {
                // Suppress the selection callback's expensive resetContent during a live swipe.
                expectedContentQueue.push(newSelection)
                state.editor.setSelectionRange(safeSelection)
            }
        }

        /**
         * Commits a swipe-delete selection and synchronizes the resulting collapsed cursor.
         */
        fun deleteSelectionFromGesture(selection: EditorSelection): Boolean {
            val newSelection = state.editor.deleteSelection(selection) ?: return false
            updateSelectionFromGesture(newSelection)
            return true
        }

        /**
         * Re-reads native editor state after a direct InputConnection edit. This deliberately does
         * not call setSelection: browsers may still be handling inline autocomplete removal.
         */
        private fun resyncContentAfterNativeEdit() {
            val editor = state.editor
            val selection = editor.getSelectionRange() ?: return
            val newSelection = K3TextRange(selection.start, selection.end)
            resetContent(newSelection, editor.getSurroundingText(50, 10))
            expectedContentQueue.push(state.content.selection)
        }

        /**
         * Deletes [count] units before the cursor, or the current selection if there is one.
         */
        fun deletePrecisely(count: Int, unit: OperationUnit) {
            if (count <= 0) return
            val editor = state.editor
            if (count == 1 && unit == OperationUnit.CHARACTERS) {
                // A plain backspace must go straight to the editor. Browsers delete inline URL
                // autocomplete first when they receive deleteSurroundingText; calling setSelection
                // or finishComposingText here destroys that state and deletes the typed character.
                if (!editor.deleteSelectedTextIfAny()) {
                    editor.deleteSurroundingText(1, 0)
                }
                resyncContentAfterNativeEdit()
                return
            }
            val selection = editor.getSelectionRange() ?: return
            if (selection.end > selection.start) {
                deleteSelectionFromGesture(selection)
                return
            }
            val target = editor.selectionBefore(count, unit, selection) ?: return
            if (target.end > target.start) {
                deleteSelectionFromGesture(target)
            }
        }

        /**
         * Extends the current selection by [count] units to the left.
         */
        fun selectPrecisely(count: Int, unit: OperationUnit) {
            if (count <= 0) return
            val editor = state.editor
            val selection = editor.getSelectionRange() ?: return
            val target = editor.selectionBefore(count, unit, selection) ?: return
            updateSelectionFromGesture(target)
        }

        fun setImeUiMode(mode: ImeUiMode) {
            state = state.copy(flags = state.flags.withImeUiMode(mode))
        }

        fun cycleImeUiMode(delta: Int) {
            val values = ImeUiMode.entries
            val newMode = values[Math.floorMod(state.flags.imeUiMode.ordinal + delta, values.size)]
            setImeUiMode(newMode)
        }

        override fun emit(value: K3StringOrDescriptor) {
            val interceptor = emitInterceptor.load()
            if (interceptor != null) {
                interceptor.onEmit(value)
                return
            }
            super.emit(value)
        }

        private val isTeluguPhonetic: Boolean
            get() = activeLayoutId == "telugu-phonetic"

        /**
         * Replaces the Telugu output for the current raw Latin buffer entirely in the native editor.
         * K3's whole-context emit marks the completed word as composing, which makes the next
         * transliteration replace or merge into that word.
         */
        private fun replacePhoneticOutput(newOutput: String) {
            val oldOutput = phoneticOutput
            // Replace our own transliteration with a single native editor edit. K3's whole-context
            // emitText marks the word as composing, and the next edit then replaces that completed
            // word instead of appending to it.
            state.editor.finishComposingText()
            if (oldOutput.isNotEmpty()) {
                state.editor.replaceTextBeforeCursorNatively(oldOutput.length, newOutput)
            } else if (newOutput.isNotEmpty()) {
                state.editor.commitTextAtCursor(newOutput)
            }
            resyncContentAfterNativeEdit()
        }

        override fun emitText(value: K3String) {
            glideBackspaceWordLength = 0
            val text = value.toText()
            if (isTeluguPhonetic && text.length == 1 && text[0].let { it in 'a'..'z' || it in 'A'..'Z' }) {
                val raw = phoneticLatin + text.lowercase()
                val output = TeluguPhoneticTransliterator.transliterate(raw)
                replacePhoneticOutput(output)
                phoneticLatin = raw
                phoneticOutput = output
                expectedContentQueue.push(state.content.selection)
                reevaluateInputShiftState()
                return
            }
            clearPhoneticBuffer()
            if (prefs.correction.autoReplaceMistypedWords.get() &&
                text.length == 1 && (text[0].isWhitespace() || text[0] in ".,!?;:")
            ) {
                val typedWord = state.content.compositionText?.toString().orEmpty().ifEmpty {
                    state.content.surroundingText.textBefore.takeLastWhile {
                        it.isLetterOrDigit() || it == '\''
                    }
                }
                val autoCandidate = if (typedWord.isEmpty()) {
                    null
                } else {
                    val beforeWord = state.content.surroundingText.textBefore
                        .removeSuffix(typedWord)
                    val prevChar = beforeWord.lastOrNull()
                    val standaloneI = typedWord != "i" ||
                        (prevChar == null || !prevChar.isLetterOrDigit() && prevChar != '\'')
                    if (!standaloneI) {
                        null
                    } else {
                        nlpManager.getAutoCommitCandidate(typedWord)?.takeIf {
                            it.text.toString() != typedWord
                        }
                    }
                }
                if (autoCandidate != null) {
                    emitCompletion(autoCandidate, forceAutoSpace = false)
                    super.emitText(value)
                    expectedContentQueue.push(state.content.selection)
                    reevaluateInputShiftState()
                    return
                }
                // "hello ." -> "hello." is a common mistyped-punctuation case.
                if (text[0] in ".,!?;:") {
                    val before = state.editor.getTextBeforeSelection(4).orEmpty()
                    if (before.endsWith(' ') &&
                        before.trimEnd().lastOrNull()?.let { it.isLetterOrDigit() } == true
                    ) {
                        deletePrecisely(1, OperationUnit.CHARACTERS)
                    }
                }
            }
            // Insert short ordinary output directly at the native cursor only for editors where
            // composing is disabled. K3's whole-context replacement can insert a stale prefix in
            // middle-of-text edits there (e.g. "IND VS NZ" becoming "IND VSINDIND VS NZ" after
            // typing only "I"). In composing editors such as Joplin, a direct commit would replace
            // K3's stale composing region and delete the previous word, so keep the K3 path there.
            if (!state.editor.info.isRawInputEditor && !state.flags.isComposingEnabled &&
                text.length <= MAX_NATIVE_INSERT_LENGTH && state.editor.commitTextAtCursor(text)
            ) {
                // resetContent inside resync already reevaluates capitalization.
                resyncContentAfterNativeEdit()
                return
            }
            super.emitText(value)
            expectedContentQueue.push(state.content.selection)
            reevaluateInputShiftState()
        }

        override fun emitDescriptor(descriptor: K3Descriptor) {
            glideBackspaceWordLength = 0
            val windowController = FlorisImeService.windowControllerOrNull()
            when (descriptor) {
                // TODO once k3lp supports this remove
                ImeActions.Delete -> emitForwardDelete()
                // TODO evaluate use of modern cursor anchor API instead of sending raw key events
                ImeActions.ArrowDown -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                ImeActions.ArrowLeft -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                ImeActions.ArrowRight -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                ImeActions.ArrowUp -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                // TODO clipboard manager needs a rewrite to match the new state paradigm
                ImeActions.ClipboardCopy -> {
                    // Prefer the app's own action; it preserves app-specific clipboard data and
                    // works even when our cached selection is stale.
                    if (!state.editor.performClipboardCopy()) {
                        val text = state.editor.getSelectedText()
                            .ifEmpty { state.content.surroundingText.textSelected }
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                            clipboardManager.forceSyncToSystem(clipboardManager.primaryClip)
                        }
                    }
                }
                ImeActions.ClipboardCut -> {
                    if (!state.editor.performClipboardCut()) {
                        val text = state.editor.getSelectedText()
                            .ifEmpty { state.content.surroundingText.textSelected }
                        val selection = state.editor.getSelectionRange()
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                            clipboardManager.forceSyncToSystem(clipboardManager.primaryClip)
                        }
                        if (selection != null && selection.end > selection.start) {
                            deleteSelectionFromGesture(selection)
                        }
                    }
                }
                ImeActions.ClipboardPaste -> {
                    if (!state.editor.performClipboardPaste()) {
                        emitClipboardItem(clipboardManager.primaryClip)
                    }
                }
                ImeActions.ClipboardClearHistory -> clipboardManager.clearHistory()
                ImeActions.ClipboardClearFullHistory -> clipboardManager.clearFullHistory()
                ImeActions.ClipboardClearPrimaryClip -> {
                    if (prefs.clipboard.clearPrimaryClipAffectsHistoryIfUnpinned.get()) {
                        clipboardManager.primaryClip?.let { clipboardManager.deleteClip(it, onlyIfUnpinned = true) }
                    }
                    clipboardManager.updatePrimaryClip(null)
                    // TODO we have no ref here:
                    //  appContext.showShortToastSync(R.string.clipboard__cleared_primary_clip)
                }
                ImeActions.SelectAll -> state.editor.performSelectAll()
                ImeActions.ToggleAutocorrect -> scope.launch {
                    prefs.correction.autoReplaceMistypedWords.set(
                        !prefs.correction.autoReplaceMistypedWords.get(),
                    )
                }
                ImeActions.ShowImeWindow -> FlorisImeService.showUi()
                ImeActions.HideImeWindow -> FlorisImeService.hideUi()
                ImeActions.ShowTextPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT),
                    )
                }
                ImeActions.ShowMediaPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.MEDIA),
                    )
                }
                ImeActions.ShowClipboardPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.CLIPBOARD),
                    )
                }
                ImeActions.Undo -> state.editor.performUndo()
                ImeActions.Redo -> state.editor.performRedo()
                ImeActions.Settings -> FlorisImeService.launchSettings()
                ImeActions.ShowInputMethodPicker -> FlorisImeService.showImePicker()
                ImeActions.SwitchToPrevInputMethod -> FlorisImeService.switchToPrevInputMethod()
                ImeActions.SwitchToNextInputMethod -> FlorisImeService.switchToNextInputMethod()
                ImeActions.ShowSubtypePicker -> {
                    state = state.copy(
                        flags = state.flags
                            .withSubtypeSelectionVisible(!state.flags.isSubtypeSelectionVisible),
                    )
                }
                ImeActions.SwitchToPrevSubtype -> FlorisApplication.getHack().subtypeManager.value.switchToPrevSubtype()
                ImeActions.SwitchToNextSubtype -> {
                    // Like the old FlorisBoard language key: tapping it opens the subtype
                    // selection panel so the user can see and pick the target language.
                    state = state.copy(
                        flags = state.flags
                            .withSubtypeSelectionVisible(!state.flags.isSubtypeSelectionVisible),
                    )
                }
                ImeActions.ToggleActionsEditor -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsEditorVisible(!state.flags.isActionsEditorVisible),
                    )
                }
                ImeActions.ToggleActionsOverflow -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsOverflowVisible(!state.flags.isActionsOverflowVisible),
                    )
                }
                ImeActions.ToggleCompactLayout -> windowController?.actions?.toggleCompactLayout()
                ImeActions.ToggleFloatingWindow -> windowController?.actions?.toggleFloatingWindow()
                ImeActions.ToggleResizeMode -> windowController?.editor?.toggleEnabled()
                ImeActions.CompactLayoutToLeft -> windowController?.actions?.compactLayoutToLeft()
                ImeActions.CompactLayoutToRight -> windowController?.actions?.compactLayoutToRight()
                ImeActions.ExternalVoiceInput -> FlorisImeService.switchToVoiceInputMethod()
                else -> super.emitDescriptor(descriptor)
            }
            reevaluateInputShiftState()
        }

        override fun emitBackspace() {
            val glideWordLength = glideBackspaceWordLength
            if (glideWordLength > 0) {
                // "Pressing delete right after a glide deletes the whole word": consume the whole
                // just-committed glide output in one native deletion.
                glideBackspaceWordLength = 0
                clearPhoneticBuffer()
                deletePrecisely(glideWordLength, OperationUnit.CHARACTERS)
                reevaluateInputShiftState()
                return
            }
            if (isTeluguPhonetic && phoneticLatin.isNotEmpty()) {
                val raw = phoneticLatin.dropLast(1)
                val output = TeluguPhoneticTransliterator.transliterate(raw)
                replacePhoneticOutput(output)
                phoneticLatin = raw
                phoneticOutput = output
                return
            }
            clearPhoneticBuffer()
            // Native deletion is essential here: the K3 base implementation rewrites the whole
            // 50-character context as a composing region, which crosses newlines in list editors and
            // makes one backspace delete an entire bullet/numbered point.
            deletePrecisely(1, OperationUnit.CHARACTERS)
            reevaluateInputShiftState()
        }

        override fun emitEnter() {
            glideBackspaceWordLength = 0
            val info = state.editor.info
            val isShiftPressed = false // TODO inputEventDispatcher.isPressed(KeyCode.SHIFT)
            if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine && isShiftPressed) {
                emitEnterKey()
            } else {
                when (val action = info.imeOptions.action) {
                    ImeOptions.Action.UNSPECIFIED,
                    ImeOptions.Action.DONE,
                    ImeOptions.Action.GO,
                    ImeOptions.Action.NEXT,
                    ImeOptions.Action.PREVIOUS,
                    ImeOptions.Action.SEARCH,
                    ImeOptions.Action.SEND -> emitEnterAction(action)
                    else -> emitEnterKey()
                }
            }
        }

        fun emitEnterKey() {
            val info = state.editor.info
            if (info.isRawInputEditor) {
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
            } else {
                emitText(NEWLINE_SEQ)
            }
        }

        fun emitEnterAction(action: ImeOptions.Action) {
            state.editor.performEditorAction(action)
        }

        fun emitForwardDelete() {
            glideBackspaceWordLength = 0
            // Prefer the real editor state and app-level fallbacks. Custom editors such as PDF
            // viewers can ignore deleteSurroundingText, so ImeEditor falls back to key events and
            // explicit native selection deletion before we give up.
            if (state.editor.performForwardDelete()) {
                resyncContentAfterNativeEdit()
                return
            }
            // Legacy state-based fallback for editors that provide no readable text snapshot.
            if (state.content.selection.isNotCollapsed()) {
                emitBackspace()
            } else {
                val newSurroundingText = state.content.surroundingText.copy(
                    textAfter = state.content.surroundingText.textAfter.let { text ->
                        // TODO unicode
                        if (text.isEmpty()) text else text.substring(1)
                    },
                )
                state = state.copy(
                    content = state.content.copy(
                        surroundingText = newSurroundingText,
                    ),
                )
                state.editor.deleteSurroundingText(
                    charsBefore = 0,
                    charsAfter = 1, // TODO
                )
                expectedContentQueue.push(state.content.selection)
            }
        }

        fun emitCandidate(candidate: SuggestionCandidate) {
            glideBackspaceWordLength = 0
            // Incognito picks must not influence the local user dictionary.
            if (!state.flags.isIncognitoMode) {
                scope.launch {
                    runCatching {
                        candidate.sourceProvider?.notifySuggestionAccepted(
                            FlorisApplication.getHack().subtypeManager.value.activeSubtype,
                            candidate,
                        )
                    }
                }
            }
            when (candidate) {
                is ClipboardSuggestionCandidate -> emitClipboardItem(candidate.clipboardItem)
                else -> emitCompletion(candidate)
            }
        }

        fun emitClipboardItem(item: ClipboardItem?): Boolean {
            return item != null && when (item.type) {
                ItemType.TEXT -> {
                    val text = item.text?.asK3String()
                    if (text != null) {
                        emitText(text)
                        true
                    } else {
                        false
                    }
                }
                ItemType.IMAGE, ItemType.VIDEO -> {
                    state.editor.performClipboardPasteMedia(item)
                }
            }.also {
                if (prefs.clipboard.historyHideOnPaste.get()) {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT)
                    )
                }
            }
        }

        /** Returns the text actually committed, so the glide backspace shortcut can use its length. */
        fun emitGlideText(text: String, transliterate: Boolean): String {
            if (text.isEmpty() || state.editor.info.isRawInputEditor) return ""
            val output = if (transliterate) {
                TeluguPhoneticTransliterator.transliterate(text)
            } else {
                text
            }
            if (output.isEmpty()) return ""
            val autoSpace = prefs.suggestion.autoSpaceAfterSuggestionPick.get() &&
                state.content.surroundingText.textAfter.firstOrNull()?.isWhitespace() != true
            val finalText = if (autoSpace) "$output " else output
            if (!transliterate && activeLayoutId == "telugu-phonetic") {
                // Resolved candidates are already final output; do not feed them back through the
                // raw-Latin phonetic buffer or K3's composing region.
                clearPhoneticBuffer()
                state.editor.finishComposingText()
                state.editor.commitTextAtCursor(finalText)
                resyncContentAfterNativeEdit()
                expectedContentQueue.push(state.content.selection)
                reevaluateInputShiftState()
            } else {
                emitText(finalText.asK3String())
            }
            return finalText
        }

        private fun hasHorizontalSpaceAfterCursor(): Boolean {
            return state.editor.getTextAfterSelection(1)?.firstOrNull()?.let { it == ' ' || it == '\t' } == true
        }

        private fun hasHorizontalSpaceBeforeCursor(): Boolean {
            return state.editor.getTextBeforeSelection(2)?.lastOrNull()?.let { it == ' ' || it == '\t' } == true
        }

        fun emitCompletion(candidate: SuggestionCandidate, forceAutoSpace: Boolean? = null): Boolean {
            val text = candidate.text.toString()
            if (text.isEmpty() || state.editor.info.isRawInputEditor) return false
            val content = state.content
            val composition = content.composition
            val compositionText = content.compositionText
            val hasComposition = !isTeluguPhonetic &&
                composition != null && compositionText != null && composition.min < content.selection.min
            // Read the word and lookahead from the native editor. Cached K3 content can lag in web
            // and plain-text editors, which previously made auto-space think a space already existed.
            val nativeBefore = state.editor.getTextBeforeSelection(100).orEmpty()
            val currentWord = if (isTeluguPhonetic && phoneticOutput.isNotEmpty()) {
                phoneticOutput
            } else {
                nativeBefore.takeLastWhile { it.isLetterOrDigit() || it == '\'' }
            }
            val currentWordLength = currentWord.codePointCount(0, currentWord.length)
            val currentWordNativeLength = currentWord.length
            clearPhoneticBuffer()
            // A newline is not an existing word separator for auto-space. Joplin/CodeMirror can
            // report the end-of-line newline as the next character, which previously suppressed
            // the space entirely.
            val autoSpace = forceAutoSpace ?: (
                prefs.suggestion.autoSpaceAfterSuggestionPick.get() && !hasHorizontalSpaceAfterCursor()
                )
            // Commit the word and its trailing space in one editor write. A separate emitText(" ")
            // performs another whole-context composing replace, which some rich editors drop.
            val finalText = if (autoSpace) "$text " else text
            return if (hasComposition) {
                // Replace the currently composed word with the candidate text. This mirrors the logic of
                // `emitText`, but removes the composed word from the tracked input context first.
                val tail = compositionText!!.asK3String().normalize(NormalizationForm.NFD)
                val headLength = (content.inputContext.length - tail.length).coerceAtLeast(0)
                val newInputContext = buildK3String {
                    append(content.inputContext.slice(0..<headLength))
                    append(finalText.asK3String())
                }
                val newSurroundingText = content.surroundingText.copy(
                    textBefore = newInputContext.normalize(NormalizationForm.NFC).toText(),
                )
                val range = content.offset..<content.selection.min
                val newSelection = K3TextRange(content.offset + newSurroundingText.textBefore.length)
                val newComposition = evaluateCompositionOf(state.model, newSelection, newSurroundingText)
                state = state.copy(
                    content = content.copy(
                        selection = newSelection,
                        composition = newComposition,
                        surroundingText = newSurroundingText,
                        inputContext = newInputContext,
                    ),
                )
                expectedContentQueue.push(state.content.selection)
                reevaluateInputShiftState()
                state.editor.replaceText(range, newSurroundingText.textBefore, newSelection, newComposition)
                true
            } else {
                // Delete the typed word and commit the candidate in a single native edit. Doing the
                // deletion first made the typed word visibly blink before the suggestion appeared.
                if (!state.editor.replaceTextBeforeCursorNatively(currentWordNativeLength, finalText)) {
                    deletePrecisely(currentWordLength, OperationUnit.CHARACTERS)
                    reevaluateInputShiftState()
                    super.emitText(finalText.asK3String())
                    expectedContentQueue.push(state.content.selection)
                    reevaluateInputShiftState()
                } else {
                    // Some plain-text web editors keep the word but drop the trailing space from a
                    // combined replacement. Verify from the native cursor and add only if missing.
                    if (autoSpace && !hasHorizontalSpaceBeforeCursor()) {
                        state.editor.commitTextAtCursor(" ")
                    }
                    resyncContentAfterNativeEdit()
                }
                true
            }
        }

        override fun switchTouchLayer(newTouchLayerId: K3LayerId) {
            if (newTouchLayerId.isTextLayer()) {
                super.switchTouchLayer(state.flags.inputShiftState.correspondingLayerId)
            } else {
                super.switchTouchLayer(newTouchLayerId)
            }
        }

        fun cycleInputShiftState(isDoubleTap: Boolean) {
            val shiftKeyBehavior = prefs.typing.shiftKeyBehavior.get()
            val oldInputShiftState = state.flags.inputShiftState
            val newInputShiftState = when (shiftKeyBehavior) {
                ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP -> when {
                    isDoubleTap -> InputShiftState.CAPS_LOCK
                    oldInputShiftState == InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    else -> InputShiftState.UNSHIFTED
                }
                ShiftKeyBehavior.CAPSLOCK_BY_CYCLE -> when (oldInputShiftState) {
                    InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    InputShiftState.SHIFTED_MANUAL -> InputShiftState.CAPS_LOCK
                    InputShiftState.SHIFTED_AUTOMATIC -> InputShiftState.UNSHIFTED
                    InputShiftState.CAPS_LOCK -> InputShiftState.UNSHIFTED
                }
            }
            state = state.copy(
                touchLayerId = newInputShiftState.correspondingLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun revertCycleInputShiftState(
            newTouchLayerId: K3LayerId,
            newInputShiftState: InputShiftState,
        ) {
            state = state.copy(
                touchLayerId = newTouchLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun reevaluateInputShiftState() {
            val isPeekOngoing = false // TODO
            if (state.flags.inputShiftState == InputShiftState.CAPS_LOCK || isPeekOngoing) return
            // Query the editor directly: the cached K3 content can lag by one character in browsers,
            // which made auto-capitalization miss the start of the next word.
            val textBefore = state.editor.getTextBeforeSelection(50).orEmpty()
            val capsMode = InputAttributes.CapsMode.fromFlags(
                TextUtils.getCapsMode(textBefore, textBefore.length, state.editor.info.inputAttributes.raw)
            )
            val shift = prefs.typing.autoCapitalization.get()
                && capsMode != InputAttributes.CapsMode.NONE
                // && subtypeManager.activeSubtype.primaryLocale.supportsCapitalization
            val inputShiftState = when {
                shift -> InputShiftState.SHIFTED_AUTOMATIC
                else -> InputShiftState.UNSHIFTED
            }
            val touchLayerId = when {
                state.touchLayerId.isTextLayer() -> inputShiftState.correspondingLayerId
                else -> state.touchLayerId
            }
            // Most keystrokes do not change capitalization. Avoid publishing a brand-new state (and
            // recomposing the whole keyboard plus re-running suggestions) when nothing changed.
            if (touchLayerId == state.touchLayerId && inputShiftState == state.flags.inputShiftState) {
                return
            }
            state = state.copy(
                touchLayerId = touchLayerId,
                flags = state.flags.withInputShiftState(inputShiftState),
            )
        }

        override fun resetContent(newSelection: K3TextRange, newSurrounding: K3SurroundingText) {
            clearPhoneticBuffer()
            super.resetContent(newSelection, newSurrounding)
            reevaluateInputShiftState()
        }

        override fun resetContent() {
            clearPhoneticBuffer()
            super.resetContent()
            reevaluateInputShiftState()
        }

        fun handleFinishInputView() {
            resetContent()
            state = state.copy(editor = ImeEditor.Disconnected)
            expectedContentQueue.clear()
            scope.launch { nlpManager.clearSuggestions() }
        }

        override fun evaluateCompositionOf(
            model: K3Model,
            selection: K3TextRange,
            surroundingText: K3SurroundingText
        ): K3TextRange? {
            if (isTeluguPhonetic || selection.isNotCollapsed() || !state.flags.isComposingEnabled) {
                return null
            }
            // TODO rework how we get the primary locale
            val locale = FlorisLocale.fromTag(model.locales.getOrElse(0) { "" })
            return breakIterators.word(locale) {
                it.setText(surroundingText.textBefore)
                val end = it.last()
                val isWord = it.ruleStatus != BreakIterator.WORD_NONE
                if (isWord) {
                    val start = it.previous().let { pos ->
                        // Include Emoji indicator in local composing. This is required so that emoji suggestion indicator
                        // can be detected in the composing text.
                        (pos - 1).takeIf { updatedPos ->
                            surroundingText.textBefore.getOrNull(updatedPos) == EmojiSuggestionType.LEADING_COLON.prefix.first()
                        } ?: pos
                    }
                    val offset = (selection.min - surroundingText.textBefore.length).coerceAtLeast(0)
                    K3TextRange(start + offset, end + offset)
                } else {
                    null
                }
            }
        }
    }

    companion object {
        private val NEWLINE_SEQ = "\n".asK3String()
        private val SPACE_SEQ = " ".asK3String()
        // Ordinary key output is one code point; allow a few for transforms and auto-space, but keep
        // longer/glide/pasted text on the K3 path where whole-context handling is intentional.
        private const val MAX_NATIVE_INSERT_LENGTH = 16
    }
}

enum class OperationUnit {
    CHARACTERS,
    WORDS;
}

enum class OperationScope {
    BEFORE_CURSOR,
    AFTER_CURSOR;
}

fun interface EmitInterceptor {
    fun onEmit(value: K3StringOrDescriptor)
}

private class ExpectedContentQueue {
    private val list = mutableListOf<K3TextRange>()

    fun popUntilOrNull(predicate: (K3TextRange) -> Boolean): K3TextRange? {
        while (list.isNotEmpty()) {
            val item = list.removeAt(0)
            if (predicate(item)) return item
        }
        return null
    }

    fun push(selection: K3TextRange) {
        list.add(selection)
        while (list.size > 8) {
            list.removeAt(0)
        }
    }

    fun clear() {
        list.clear()
    }
}

fun K3Content.cursorCapsMode(inputAttributes: InputAttributes): InputAttributes.CapsMode {
    return InputAttributes.CapsMode.fromFlags(
        TextUtils.getCapsMode(surroundingText.textBefore, surroundingText.textBefore.length, inputAttributes.raw)
    )
}
