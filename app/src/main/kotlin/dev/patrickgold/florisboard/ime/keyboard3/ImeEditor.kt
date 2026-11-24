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

import android.content.ClipDescription
import android.content.ContentUris
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.nlp.BreakIterators
import dev.patrickgold.florisboard.lib.FlorisLocale
import org.k3lp.runtime.K3Editor
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

data class EditorSelection(val start: Int, val end: Int) {
    init {
        require(start >= 0 && end >= start)
    }
}

private data class EditorContentSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

open class ImeEditor(
    val ic: WeakReference<InputConnection>,
    val info: FlorisEditorInfo,
) : K3Editor {
    private val breakIterators = BreakIterators()

    fun getSurroundingText(charsBefore: Int, charsAfter: Int): K3SurroundingText {
        val ic = ic.get() ?: return K3SurroundingText.Empty
        val textBeforeCursor = ic.getTextBeforeCursor(charsBefore, 0)?.toString()
        val textAfterCursor = ic.getTextAfterCursor(charsAfter, 0)?.toString()
        val textSelected = ic.getSelectedText(0)?.toString().orEmpty()
        if (textBeforeCursor != null && textAfterCursor != null &&
            (textBeforeCursor.isNotEmpty() || textAfterCursor.isNotEmpty() || textSelected.isNotEmpty())
        ) {
            return K3SurroundingText(textBeforeCursor, textSelected, textAfterCursor)
        }
        // Browsers and launcher search fields often do not implement getTextBeforeCursor properly.
        // Fall back to ExtractedText so suggestions still receive the current word.
        val request = ExtractedTextRequest().apply {
            hintMaxLines = 10
            hintMaxChars = 10000
        }
        val extracted: ExtractedText = ic.getExtractedText(request, 0) ?: return K3SurroundingText.Empty
        val all = extracted.text?.toString().orEmpty()
        val startOffset = extracted.startOffset.coerceIn(0, all.length)
        val selectionStart = (extracted.selectionStart - startOffset).coerceIn(0, all.length)
        val selectionEnd = (extracted.selectionEnd - startOffset).coerceIn(selectionStart, all.length)
        return K3SurroundingText(
            textBefore = all.substring(0, selectionStart).takeLast(charsBefore),
            textSelected = if (textSelected.isNotEmpty()) {
                textSelected
            } else {
                all.substring(selectionStart, selectionEnd)
            },
            textAfter = all.substring(selectionEnd).take(charsAfter),
        )
    }

    /**
     * Returns the current selection in absolute editor coordinates. The position is derived from
     * the text before the selection, which works across editors and does not depend on the cursor
     * anchor callback being delivered.
     */
    fun getSelectionRange(): EditorSelection? {
        val ic = ic.get() ?: return null
        val textBefore = ic.getTextBeforeCursor(SELECTION_LOOKBACK, 0)?.toString() ?: return null
        val textSelected = ic.getSelectedText(0)?.toString() ?: ""
        val start = textBefore.length
        return EditorSelection(start, start + textSelected.length)
    }

    /**
     * Returns the text before the current selection. Gestures capture this once at touch-down so
     * long swipes do not repeatedly query the editor while the selection is moving.
     */
    fun getTextBeforeSelection(limit: Int = SELECTION_LOOKBACK): String? {
        val ic = ic.get() ?: return null
        return ic.getTextBeforeCursor(limit, 0)?.toString()
    }

    /**
     * Sets the native selection so Android draws the usual text selector handles/highlight.
     */
    fun setSelectionRange(selection: EditorSelection) {
        val ic = ic.get() ?: return
        val start = selection.start.coerceAtLeast(0)
        val end = selection.end.coerceAtLeast(start)
        ic.beginBatchEdit()
        ic.setSelection(start, end)
        ic.endBatchEdit()
    }

    /**
     * Expands [base] to the left by [count] characters or words, using the same Unicode-aware
     * measurement as the rest of the editor. Reversing the swipe simply calls this again with a
     * smaller count, which shrinks the selection without deleting any text.
     */
    fun selectionBefore(
        count: Int,
        unit: OperationUnit,
        base: EditorSelection,
    ): EditorSelection? {
        val textBefore = getTextBeforeSelection() ?: return null
        return selectionBefore(count, unit, base, textBefore)
    }

    /**
     * Calculates a selection relative to a fixed [base] using text captured at gesture start.
     * Keeping the text fixed is both faster and avoids the moving-selection rounding that made
     * long swipes stall after a few words.
     */
    fun selectionBefore(
        count: Int,
        unit: OperationUnit,
        base: EditorSelection,
        textBefore: String,
    ): EditorSelection {
        if (count <= 0) return base
        val length = when (unit) {
            OperationUnit.CHARACTERS -> breakIterators.measureLastUChars(
                text = textBefore,
                numUnicodeChars = count,
                locale = FlorisLocale.default(),
            )
            OperationUnit.WORDS -> breakIterators.measureLastUWords(
                text = textBefore,
                numUnicodeWords = count,
                locale = FlorisLocale.default(),
            )
        }
        val start = (base.start - length).coerceAtLeast(0)
        return EditorSelection(start, base.end.coerceAtLeast(base.start))
    }

    /**
     * Deletes the given selection in one batch and leaves a collapsed cursor at its start.
     */
    fun deleteSelection(selection: EditorSelection): EditorSelection? {
        if (selection.end <= selection.start) return selection
        val ic = ic.get() ?: return null
        val length = selection.end - selection.start
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.setSelection(selection.end, selection.end)
        ic.deleteSurroundingText(length, 0)
        ic.setSelection(selection.start, selection.start)
        ic.endBatchEdit()
        return EditorSelection(selection.start, selection.start)
    }

    /**
     * Deletes an existing native selection without first moving the cursor or finishing composing.
     * Browsers keep URL inline autocomplete outside the normal composing state; moving the selection
     * discards it, so a single backspace must let the editor delete its own selection.
     */
    fun deleteSelectedTextIfAny(): Boolean {
        val ic = ic.get() ?: return false
        if (ic.getSelectedText(0)?.toString().isNullOrEmpty()) return false
        ic.beginBatchEdit()
        ic.commitText("", 1)
        ic.endBatchEdit()
        return true
    }

    /**
     * Commits ordinary typed text at the real native cursor. This bypasses K3's whole-context
     * replacement, which can insert a stale prefix when the cursor is moved into the middle of text.
     */
    fun commitTextAtCursor(text: String): Boolean {
        val ic = ic.get() ?: return false
        if (text.isEmpty()) return true
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        ic.endBatchEdit()
        return true
    }

    /** Fresh lookahead from the native editor; cached K3 content can lag in web/plain-text editors. */
    fun getTextAfterSelection(limit: Int = 1): String? {
        return ic.get()?.getTextAfterCursor(limit, 0)?.toString()
    }

    /**
     * Replaces the word before the cursor and the candidate in one native editor write, so the
     * intermediate "typed word disappearing" frame is never drawn.
     */
    fun replaceTextBeforeCursorNatively(deleteChars: Int, text: String): Boolean {
        val ic = ic.get() ?: return false
        ic.beginBatchEdit()
        if (deleteChars > 0) {
            ic.deleteSurroundingText(deleteChars, 0)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        return true
    }

    // TODO this function is very heavy => we need to optimize k3lp to provide:
    //  insertAtSelection() (for simple appends)
    //  deleteAroundSelection() for bksp/fw delete
    //  in some cases replaceText() is needed due to the transform rules, but we abuse it waaaay too much
    override fun replaceText(
        range: IntRange,
        text: String,
        newSelection: K3TextRange,
        newComposition: K3TextRange?,
    ) {
        val ic = ic.get() ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()

        // K3 asks us to replace an absolute context range, but browsers and rich editors often give
        // us stale surrounding text. Never rewrite that whole range: derive the smallest edit from
        // the native selection/cursor so preceding paragraphs and list points cannot be overwritten.
        val selectedLength = ic.getSelectedText(0)?.length ?: 0
        if (selectedLength > 0) {
            // commitText replaces the current native selection; an empty string deletes it.
            ic.commitText(text, 1)
        } else {
            val contextLength = (range.last + 1 - range.first).coerceAtLeast(0)
            val probe = ic.getTextBeforeCursor(contextLength, 0)?.toString().orEmpty()
            val actual = if (probe.length >= contextLength) probe.takeLast(contextLength) else probe
            val currentWordLength = probe.takeLastWhile { it.isLetterOrDigit() || it == '\'' }.length
            // A normal auto-correct/replacement only touches the current word (plus a space). If the
            // requested context diverges earlier than that, the K3 state is stale: cap the deletion
            // instead of trusting it and deleting whole list points or paragraphs.
            val maxReplace = (currentWordLength + 2).coerceAtLeast(8)
            when {
                // Normal typing: existing context is an exact prefix of the requested context.
                // Commit only the newly appended characters at the native cursor.
                text.startsWith(actual) -> {
                    val insertion = text.substring(actual.length)
                    if (insertion.isNotEmpty()) {
                        ic.commitText(insertion, 1)
                    }
                }
                // Native deletion already removed a suffix; delete only the safe tail.
                actual.startsWith(text) -> {
                    val deleteCount = (actual.length - text.length).coerceAtMost(maxReplace)
                    if (deleteCount > 0) {
                        ic.deleteSurroundingText(deleteCount, 0)
                    }
                }
                else -> {
                    var common = 0
                    val limit = minOf(actual.length, text.length)
                    while (common < limit && actual[common] == text[common]) {
                        common++
                    }
                    val oldTail = actual.length - common
                    val newTail = text.substring(common)
                    if (oldTail <= maxReplace && newTail.length <= maxReplace) {
                        if (oldTail > 0) ic.deleteSurroundingText(oldTail, 0)
                        if (newTail.isNotEmpty()) ic.commitText(newTail, 1)
                    } else if (newTail.isNotEmpty() && newTail.length <= maxReplace) {
                        // Stale context would delete too much; insert only the replacement tail.
                        ic.commitText(newTail, 1)
                    }
                }
            }
        }

        // Do not force a computed collapsed selection; commitText/deleteSurroundingText already
        // leave the cursor where it belongs, and overriding it caused caret jumps in browsers.
        if (!newSelection.isCollapsed()) {
            ic.setSelection(newSelection.start, newSelection.end)
        }
        val composition = newComposition?.takeIf { !it.isCollapsed() }
        if (composition != null) {
            ic.setComposingRegion(composition.start, composition.end)
        }
        ic.endBatchEdit()
    }

    fun deleteSurroundingText(charsBefore: Int, charsAfter: Int) {
        val ic = ic.get() ?: return
        ic.deleteSurroundingText(charsBefore, charsAfter)
    }

    fun performClipboardCopy(): Boolean {
        val ic = ic.get() ?: return false
        if (ic.performContextMenuAction(android.R.id.copy)) return true
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_C, meta(ctrl = true))
    }

    fun performClipboardCut(): Boolean {
        val ic = ic.get() ?: return false
        if (ic.performContextMenuAction(android.R.id.cut)) return true
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_X, meta(ctrl = true))
    }

    fun performClipboardPaste(): Boolean {
        val ic = ic.get() ?: return false
        if (ic.performContextMenuAction(android.R.id.paste)) return true
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_V, meta(ctrl = true))
    }

    /** Reads the current selection directly from the editor, avoiding stale cached content. */
    fun getSelectedText(): String {
        return ic.get()?.getSelectedText(0)?.toString().orEmpty()
    }

    fun performClipboardPasteMedia(item: ClipboardItem): Boolean {
        val ic = ic.get() ?: return false
        val uri = item.uri ?: return false
        val mimeTypes = item.mimeTypes
        val id = ContentUris.parseId(uri)
        val appContext = FlorisApplication.getHack() // TODO this is a hack
        val file = ClipboardFileStorage.getFileForId(appContext, id)
        if (!file.exists()) return false
        val inputContentInfo = InputContentInfoCompat(
            uri,
            ClipDescription("clipboard media file", mimeTypes.toTypedArray()),
            null,
        )
        ic.finishComposingText()
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        return InputConnectionCompat.commitContent(ic, info.base, inputContentInfo, flags, null)
    }

    /**
     * Reads a bounded snapshot of the editor text and its selection. ExtractedText is preferred
     * because custom editors (PDF viewers, for example) often return little or no text from the
     * getText*Cursor calls but do implement extracted text and setSelection correctly.
     */
    private fun getEditableSnapshot(limit: Int): EditorContentSnapshot? {
        val ic = ic.get() ?: return null
        val request = ExtractedTextRequest().apply {
            hintMaxLines = Int.MAX_VALUE
            hintMaxChars = limit
        }
        val extracted = ic.getExtractedText(request, 0)
        if (extracted != null && extracted.text != null) {
            val all = extracted.text.toString()
            val startOffset = extracted.startOffset.coerceIn(0, all.length)
            val start = (extracted.selectionStart - startOffset).coerceIn(0, all.length)
            val end = (extracted.selectionEnd - startOffset).coerceIn(start, all.length)
            return EditorContentSnapshot(all, start, end)
        }
        val before = ic.getTextBeforeCursor(limit, 0)?.toString() ?: return null
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(limit, 0)?.toString() ?: return null
        return EditorContentSnapshot(
            text = before + selected + after,
            selectionStart = before.length,
            selectionEnd = before.length + selected.length,
        )
    }

    fun performSelectAll(): Boolean {
        val ic = ic.get() ?: return false
        ic.finishComposingText()
        val fullTextLength = getEditableSnapshot(SELECT_ALL_LOOKBACK)?.text?.length ?: -1
        if (ic.performContextMenuAction(android.R.id.selectAll)) return true
        // Many WebView/browser fields do not implement SELECT_ALL but do handle Ctrl+A.
        sendDownUpKeyEvent(KeyEvent.KEYCODE_A, meta(ctrl = true))
        val selectedLength = ic.getSelectedText(0)?.length ?: 0
        if (fullTextLength < 0 || (selectedLength > 0 && selectedLength >= fullTextLength)) {
            return true
        }
        // Final fallback for editors that ignore both app actions and key events: select the whole
        // text through the native selection API so the usual handles/highlight are drawn.
        val snapshot = getEditableSnapshot(SELECT_ALL_LOOKBACK) ?: return false
        if (snapshot.text.isEmpty()) return true
        ic.beginBatchEdit()
        ic.setSelection(0, snapshot.text.length)
        ic.endBatchEdit()
        return (ic.getSelectedText(0)?.length ?: 0) > 0
    }

    /**
     * Forward-deletes the selection or the next character. Some custom editors ignore
     * deleteSurroundingText entirely, so fall back to app key events and finally to an explicit
     * native selection removal.
     */
    fun performForwardDelete(): Boolean {
        val ic = ic.get() ?: return false
        val nativeSelection = getSelectionRange()
        val hasNativeSelection = nativeSelection != null && nativeSelection.end > nativeSelection.start
        val snapshotLimit = if (hasNativeSelection) SELECT_ALL_LOOKBACK else FORWARD_DELETE_LOOKBACK
        val before = getEditableSnapshot(snapshotLimit) ?: return false

        val snapshotSelectionLength = before.selectionEnd - before.selectionStart
        val nativeSelectionLength = if (hasNativeSelection) {
            nativeSelection!!.end - nativeSelection.start
        } else {
            0
        }
        val hasSelection = snapshotSelectionLength > 0 || nativeSelectionLength > 0
        val start = if (snapshotSelectionLength > 0) {
            before.selectionStart
        } else {
            nativeSelection?.start ?: before.selectionStart
        }
        val end = when {
            snapshotSelectionLength > 0 -> before.selectionEnd
            nativeSelectionLength > 0 -> nativeSelection!!.end
            start >= before.text.length -> return true
            else -> start + Character.charCount(before.text.codePointAt(start))
        }
        val length = end - start

        ic.beginBatchEdit()
        if (hasSelection) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(0, length)
        }
        ic.endBatchEdit()
        if (editorTextChanged(ic, before.text, snapshotLimit)) return true

        sendDownUpKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL)
        if (editorTextChanged(ic, before.text, snapshotLimit)) return true

        // Last resort: select exactly the target range and delete through the native selection.
        ic.beginBatchEdit()
        ic.setSelection(end, end)
        ic.deleteSurroundingText(length, 0)
        ic.setSelection(start, start)
        ic.endBatchEdit()
        return editorTextChanged(ic, before.text, snapshotLimit)
    }

    private fun editorTextChanged(ic: InputConnection, previousText: String, limit: Int): Boolean {
        val snapshot = getEditableSnapshot(limit) ?: return false
        return snapshot.text != previousText
    }

    fun performUndo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true))
    }

    fun performRedo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true, shift = true))
    }

    fun performEditorAction(action: ImeOptions.Action) {
        val ic = ic.get() ?: return
        ic.performEditorAction(action.toInt())
    }

    private fun meta(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): Int {
        var metaState = 0
        if (ctrl) {
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt) {
            metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        return metaState
    }

    private fun InputConnection.sendDownKeyEvent(keyCode: Int, metaState: Int, downTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, downTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun InputConnection.sendUpKeyEvent(keyCode: Int, metaState: Int, downTime: Long, upTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, upTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun sendDownUpKeyEvent(keyCode: Int, metaState: Int = 0): Boolean {
        val ic = ic.get() ?: return false
        val downTime = SystemClock.uptimeMillis()
        ic.sendDownKeyEvent(keyCode, metaState, downTime)
        val upTime = SystemClock.uptimeMillis()
        ic.sendUpKeyEvent(keyCode, metaState, downTime, upTime)
        return true
    }

    override fun setComposition(newComposition: K3TextRange?) {
        val ic = ic.get() ?: return
        if (newComposition == null || newComposition.isCollapsed()) {
            ic.finishComposingText()
        } else {
            ic.setComposingRegion(newComposition.start, newComposition.end)
        }
    }

    private inline fun InputConnection.batchEdit(block: () -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        requestCursorUpdates(0)
        beginBatchEdit()
        block()
        endBatchEdit()
        requestCursorUpdates(CURSOR_UPDATES)
    }

    companion object {
        private const val SELECTION_LOOKBACK = 10000
        private const val SELECT_ALL_LOOKBACK = 100000
        private const val FORWARD_DELETE_LOOKBACK = 256

        /**
         * The input connection update cursor flags.
         *
         * MUST NOT contain filter flags, as these behave strangely with some editors!!
         */
        const val CURSOR_UPDATES = InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE

        val Disconnected = ImeEditor(
            ic = WeakReference(null),
            info = FlorisEditorInfo.Unspecified,
        )
    }
}
