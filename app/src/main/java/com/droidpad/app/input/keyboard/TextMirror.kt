package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke

/**
 * Mirrors the local IME editor text onto the host by emitting the minimum key strokes that turn
 * the previously mirrored text into the new text.
 *
 * The host runs its own Korean IME, so the last Hangul syllable we typed is still "composing" on
 * the host. Editing that syllable is done jamo-by-jamo (Backspace removes one keystroke), while
 * any other character is removed with one Backspace.
 */
class TextMirror(
    private val encoder: TextEncoder,
    private val emit: (List<KeyStroke>) -> Unit,
    private val onUnsupported: (Char) -> Unit = {},
) {
    var text: String = ""
        private set

    /** True while the host is believed to be composing the last Hangul syllable of [text]. */
    var hostComposing: Boolean = false
        private set

    fun update(newText: String) {
        val old = text
        if (old == newText) return
        val prefix = commonPrefixLength(old, newText)
        val out = ArrayList<KeyStroke>()
        var typeFrom = prefix

        val editsComposingSyllable = hostComposing && prefix == old.length - 1 &&
            Hangul.isHangul(old.last()) && prefix < newText.length && Hangul.isHangul(newText[prefix])

        if (editsComposingSyllable) {
            val oldKeys = Hangul.strokes(old.last())!!
            val run = newText.substring(prefix).takeWhile(Hangul::isHangul)
            val newKeys = run.flatMap { Hangul.strokes(it)!! }
            val same = commonPrefixLength(oldKeys, newKeys)
            repeat(oldKeys.size - same) { out += BACKSPACE }
            out += newKeys.drop(same)
            hostComposing = true
            typeFrom = prefix + run.length
        } else {
            var backspaces = 0
            for (i in prefix until old.length) {
                val c = old[i]
                backspaces += when {
                    !encoder.isSupported(c) -> 0
                    i == old.length - 1 && hostComposing && Hangul.isHangul(c) -> Hangul.strokes(c)!!.size
                    else -> 1
                }
            }
            if (prefix < old.length) hostComposing = false
            repeat(backspaces) { out += BACKSPACE }
        }

        for (i in typeFrom until newText.length) {
            val c = newText[i]
            val strokes = encoder.encode(c)
            if (strokes.isEmpty()) {
                // Count a surrogate pair (emoji…) as a single unsupported character.
                if (!(c.isLowSurrogate() && i > typeFrom && newText[i - 1].isHighSurrogate())) onUnsupported(c)
                continue
            }
            out += strokes
            hostComposing = Hangul.isHangul(c) && encoder.hostLanguage == HostLanguage.KOREAN
        }
        text = newText
        if (out.isNotEmpty()) emit(out)
    }

    /** Forget the mirrored text, e.g. after the local editor is cleared or a special key was sent. */
    fun reset() {
        text = ""
        hostComposing = false
    }

    /** A non-text key (arrow, Enter, click…) was sent: the host has finished composing. */
    fun endComposition() {
        hostComposing = false
    }

    companion object {
        val BACKSPACE = KeyStroke(KeyCodes.BACKSPACE)

        fun commonPrefixLength(a: CharSequence, b: CharSequence): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        fun <T> commonPrefixLength(a: List<T>, b: List<T>): Int {
            val n = minOf(a.size, b.size)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }
    }
}
