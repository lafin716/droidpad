package com.droidpad.app.ui.components

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.input.InputController
import com.droidpad.app.input.keyboard.AndroidKeyMap

/**
 * Invisible text editor that hosts the phone's IME (e.g. Samsung Keyboard). Every edit the IME
 * makes to our [Editable] is forwarded to [InputController], which mirrors it onto the host as
 * HID key strokes. Non-text keys (arrows, Enter, Backspace on empty text) are sent directly.
 */
class HidInputView(context: Context) : View(context) {
    var controller: InputController? = null
        set(value) {
            field?.bindEditor(null)
            field = value
            value?.bindEditor(::resetEditor)
        }

    private val editable: Editable = Editable.Factory.getInstance().newEditable("")
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private var batchDepth = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = Selection.getSelectionStart(editable).coerceAtLeast(0)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(editable).coerceAtLeast(0)
        return Connection()
    }

    fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Clears the local editor; called by the controller (main thread) after special keys. */
    private fun resetEditor() {
        if (editable.isEmpty()) return
        editable.clear()
        Selection.setSelection(editable, 0)
        BaseInputConnection.removeComposingSpans(editable)
        imm.restartInput(this)
    }

    private fun sync() {
        if (batchDepth > 0) return
        controller?.onEditorText(editable.toString())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        AndroidKeyMap.usageFor(keyCode) != null || super.onKeyUp(keyCode, event)

    /** Handles a key event from the IME or a hardware keyboard; returns true when consumed. */
    private fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return AndroidKeyMap.usageFor(event.keyCode) != null
        val c = controller ?: return false
        val keyCode = event.keyCode
        val hasShortcutMeta = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        when {
            keyCode == KeyEvent.KEYCODE_DEL && !hasShortcutMeta -> {
                val end = Selection.getSelectionEnd(editable)
                val start = Selection.getSelectionStart(editable)
                if (editable.isEmpty() || end <= 0) {
                    c.sendKey(KeyStroke(KeyCodes.BACKSPACE))
                } else {
                    val from = if (start != end) minOf(start, end) else end - 1
                    editable.delete(from, maxOf(start, end))
                    sync()
                }
            }
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_TAB -> {
                AndroidKeyMap.strokeFor(keyCode, event.metaState)?.let(c::sendKey)
            }
            !hasShortcutMeta && event.unicodeChar != 0 -> {
                insert(event.unicodeChar.toChar().toString())
            }
            else -> {
                val stroke = AndroidKeyMap.strokeFor(keyCode, event.metaState) ?: return false
                c.sendKey(stroke)
            }
        }
        return true
    }

    private fun insert(text: CharSequence) {
        val start = Selection.getSelectionStart(editable).coerceAtLeast(0)
        val end = Selection.getSelectionEnd(editable).coerceAtLeast(0)
        editable.replace(minOf(start, end), maxOf(start, end), text)
        sync()
    }

    private inner class Connection : BaseInputConnection(this@HidInputView, true) {
        override fun getEditable(): Editable = this@HidInputView.editable

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchDepth > 0) batchDepth--
            sync()
            return batchDepth > 0
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.commitText(text, newCursorPosition).also { sync() }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.setComposingText(text, newCursorPosition).also { sync() }

        override fun finishComposingText(): Boolean = super.finishComposingText().also { sync() }

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            super.setComposingRegion(start, end).also { sync() }

        override fun setSelection(start: Int, end: Int): Boolean = super.setSelection(start, end).also { sync() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (editable.isEmpty() && beforeLength > 0) {
                repeat(beforeLength.coerceAtMost(MAX_RAW_DELETES)) { controller?.sendKey(KeyStroke(KeyCodes.BACKSPACE)) }
                return true
            }
            return super.deleteSurroundingText(beforeLength, afterLength).also { sync() }
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (editable.isEmpty() && beforeLength > 0) return deleteSurroundingText(beforeLength, afterLength)
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { sync() }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            handleKey(event)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            controller?.sendKey(KeyStroke(KeyCodes.ENTER))
            return true
        }

        override fun closeConnection() {
            batchDepth = 0
            super.closeConnection()
        }
    }

    private companion object {
        const val MAX_RAW_DELETES = 64
    }
}
