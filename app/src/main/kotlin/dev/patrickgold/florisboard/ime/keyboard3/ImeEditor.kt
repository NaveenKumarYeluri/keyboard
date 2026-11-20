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

open class ImeEditor(
    val ic: WeakReference<InputConnection>,
    val info: FlorisEditorInfo,
) : K3Editor {
    private val breakIterators = BreakIterators()

    fun getSurroundingText(charsBefore: Int, charsAfter: Int): K3SurroundingText {
        val ic = ic.get() ?: return K3SurroundingText.Empty
        // TODO maybe use eet for getSurroundingText??
//        val eet = ic.getExtractedText(
//            ExtractedTextRequest().apply {
//                token = -1
//                flags = 0
//                hintMaxLines = 10
//                hintMaxChars = 10000
//            },
//            0,
//        )
//        eet.
        val textBeforeCursor = ic.getTextBeforeCursor(charsBefore, 0) ?: return K3SurroundingText.Empty
        val textAfterCursor = ic.getTextAfterCursor(charsAfter, 0) ?: return K3SurroundingText.Empty
        val textSelected = ic.getSelectedText(0) ?: ""
        //val surroundingText = TextUtils.concat(textBeforeCursor, textSelected, textAfterCursor)
        return K3SurroundingText(
            textBefore = textBeforeCursor.toString(),
            textSelected = textSelected.toString(),
            textAfter = textAfterCursor.toString(),
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
        ic.setComposingRegion(range.first, range.last + 1)
        ic.commitText(text, 1)
        ic.setSelection(newSelection.start, newSelection.end)
        setComposition(newComposition)
        ic.endBatchEdit()
    }

    fun deleteSurroundingText(charsBefore: Int, charsAfter: Int) {
        val ic = ic.get() ?: return
        ic.deleteSurroundingText(charsBefore, charsAfter)
    }

    fun performClipboardCopy(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.copy)
    }

    fun performClipboardCut(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.cut)
    }

    fun performClipboardPaste(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.paste)
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

    fun performSelectAll(): Boolean {
        val ic = ic.get() ?: return false
        ic.finishComposingText()
        return if (info.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_A, meta(ctrl = true))
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
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
