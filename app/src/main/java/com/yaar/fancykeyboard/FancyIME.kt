package com.yaar.fancykeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class FancyIME : InputMethodService() {

    private var fancyMode = true   // 👑 ON = Boss style!
    private var isUpper = false    // shift (one-shot)
    private var viewMode = 0       // 0 letters 1 sym1 2 sym2 3 emoji 4 clip
    private var lastSpaceTime = 0L
    private var shiftBtn: Button? = null
    private var toggleBtn: Button? = null
    private var sugViews: List<TextView> = emptyList()
    private var clipPreview: TextView? = null

    private val backHandler = Handler(Looper.getMainLooper())
    private val backRunnable = object : Runnable {
        override fun run() {
            deleteChar()
            backHandler.postDelayed(this, 50)
        }
    }

    // small-caps map (EXACT Boss style! 👑)
    private val smallMap = mapOf(
        'a' to 'ᴀ', 'b' to 'ʙ', 'c' to 'ᴄ', 'd' to 'ᴅ', 'e' to 'ᴇ',
        'f' to 'ꜰ', 'g' to 'ɢ', 'h' to 'ʜ', 'i' to 'ɪ', 'j' to 'ᴊ',
        'k' to 'ᴋ', 'l' to 'ʟ', 'm' to 'ᴍ', 'n' to 'ɴ', 'o' to 'ᴏ',
        'p' to 'ᴘ', 'q' to 'q', 'r' to 'ʀ', 's' to 's', 't' to 'ᴛ',
        'u' to 'ᴜ', 'v' to 'ᴠ', 'w' to 'ᴡ', 'x' to 'x', 'y' to 'ʏ', 'z' to 'ᴢ'
    )
    private val revSmall: Map<Char, Char> by lazy { smallMap.entries.associate { it.value to it.key } }

    // Q-row long-press = number! 🔢
    private val digitMap = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
    )

    // suggestion dictionary! ✨ (Roman Urdu + English)
    private val WORDS = listOf(
        "han", "acha", "achha", "theek", "ok", "boss", "haha", "nahi", "nahin",
        "mein", "main", "tum", "ap", "app", "kya", "kia", "hai", "hain", "ho",
        "aur", "yeh", "yah", "ka", "ki", "ke", "ko", "se", "me", "ne", "par",
        "tak", "ab", "kab", "jab", "tab", "wah", "oye", "hello", "hi", "bro",
        "yar", "yaar", "video", "party", "link", "bhejo", "dekho", "karo",
        "raha", "rahi", "rahe", "gaya", "gayi", "wala", "wali", "zyada",
        "kam", "shukriya", "thanks", "sorry", "please", "karte", "hota",
        "hoti", "chalo", "ruk", "suno", "batao", "kaise", "kese", "kyun",
        "kyu", "kitna", "kaun", "kon", "kidhar", "idhar", "udhar", "movie",
        "song", "game", "play", "start", "band", "khol", "subah", "raat",
        "din", "kal", "aj", "aaj", "abhi", "phir", "lekin", "magar",
        "shayad", "bilkul", "pakka", "done", "uff", "hmm", "sahi",
        "ghalat", "love", "miss", "good", "night", "morning", "bye",
        "welcome", "maza", "bhai", "dost", "khabar", "sunao", "bolo"
    )

    private fun boldOf(upper: Char): String {
        val cp = 0x1D400 + (upper.code - 65)
        return String(Character.toChars(cp))
    }

    private fun fancyWord(w: String): String {
        if (w.isEmpty()) return w
        val first = boldOf(w[0].uppercaseChar())
        val rest = w.drop(1).map { smallMap[it.lowercaseChar()]?.toString() ?: it.toString() }.joinToString("")
        return first + rest
    }

    override fun onCreateInputView(): View {
        viewMode = 0
        return makeView(layoutFor(viewMode))
    }

    private fun layoutFor(m: Int): Int = when (m) {
        1 -> R.layout.symbols_view
        2 -> R.layout.symbols2_view
        3 -> R.layout.emoji_view
        4 -> R.layout.clipboard_view
        else -> R.layout.keyboard_view
    }

    private fun setMode(m: Int) {
        viewMode = m
        setInputView(makeView(layoutFor(m)))
    }

    private fun makeView(layoutId: Int): View {
        val v = layoutInflater.inflate(layoutId, null)
        val all = mutableListOf<Button>()
        collectButtons(v, all)
        for (b in all) {
            val tag = b.tag as String
            if (tag == "BACK") {
                // press-and-hold = lagatar delete! 🔥
                b.setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            tick()
                            deleteChar()
                            backHandler.postDelayed(backRunnable, 400)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            backHandler.removeCallbacks(backRunnable)
                            true
                        }
                        else -> false
                    }
                }
            } else {
                b.setOnClickListener { tick(); onKey(tag) }
                digitMap[tag]?.let { d ->
                    b.setOnLongClickListener { tick(); currentInputConnection?.commitText(d, 1); updateSugg(); true }
                }
            }
        }
        shiftBtn = v.findViewWithTag("SHIFT")
        toggleBtn = v.findViewWithTag("TOGGLE")
        val s0: TextView? = v.findViewById(R.id.sug0)
        val s1: TextView? = v.findViewById(R.id.sug1)
        val s2: TextView? = v.findViewById(R.id.sug2)
        sugViews = listOfNotNull(s0, s1, s2)
        for (tv in sugViews) tv.setOnClickListener { tick(); pickSugg(tv.text.toString()) }
        clipPreview = v.findViewById(R.id.clip_preview)
        refreshClipPreview()
        refreshKeys()
        updateSugg()
        return v
    }

    private fun collectButtons(v: View, out: MutableList<Button>) {
        if (v is Button) { out.add(v); return }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectButtons(v.getChildAt(i), out)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isUpper = false
        refreshKeys()
        updateSugg()
    }

    private fun refreshKeys() {
        shiftBtn?.text = if (isUpper) "⇧•" else "⇧"
        toggleBtn?.text = if (fancyMode) "👑" else "🔤"
    }

    // halki vibration har key pe! 📳
    private fun tick() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(15)
            }
        } catch (e: Exception) {
        }
    }

    private fun deleteChar() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (before.isEmpty()) return
        // astral char (surrogate pair) = 2 units delete!
        if (before.last().isLowSurrogate()) ic.deleteSurroundingText(2, 0)
        else ic.deleteSurroundingText(1, 0)
        updateSugg()
    }

    // current word: (plain-lowercase, utf16-units) — fancy ko ulti parh ke! 🔄
    private fun currentWord(): Pair<String, Int> {
        val before = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: ""
        val plain = StringBuilder()
        var units = 0
        var i = before.length - 1
        while (i >= 0) {
            val c = before[i]
            if (c.isLowSurrogate()) {
                if (i - 1 < 0) break
                val cp = Character.toCodePoint(before[i - 1], c)
                if (cp in 0x1D400..0x1D419) {
                    plain.append('a' + (cp - 0x1D400))
                    units += 2
                    i -= 2
                    continue
                }
                break
            }
            if (c.isHighSurrogate()) break
            val rev = revSmall[c]
            if (rev != null) { plain.append(rev); units += 1; i--; continue }
            if (c.isLetter()) { plain.append(c.lowercaseChar()); units += 1; i--; continue }
            break
        }
        return Pair(plain.reverse().toString(), units)
    }

    private fun updateSugg() {
        if (sugViews.size < 3) return
        val (plain, _) = currentWord()
        val list: List<String> = if (plain.isEmpty()) {
            listOf("han", "acha", "ok")
        } else {
            val m = WORDS.filter { it.startsWith(plain) && it != plain }.take(2)
            val out = mutableListOf(plain)
            out.addAll(m)
            for (d in listOf("han", "acha", "ok", "boss", "haha")) {
                if (out.size >= 3) break
                if (!out.contains(d)) out.add(d)
            }
            out
        }
        for (i in 0..2) sugViews[i].text = list[i]
    }

    private fun pickSugg(w: String) {
        val ic = currentInputConnection ?: return
        val (_, units) = currentWord()
        if (units > 0) ic.deleteSurroundingText(units, 0)
        val out = if (fancyMode) fancyWord(w.lowercase()) else w
        ic.commitText("$out ", 1)
        updateSugg()
    }

    private fun clipMan(): ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun doCopy() {
        try {
            val ic = currentInputConnection
            var txt = ic?.getSelectedText(0)?.toString()
            if (txt.isNullOrEmpty()) {
                txt = ic?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            }
            if (txt.isNullOrEmpty()) {
                val b = ic?.getTextBeforeCursor(2000, 0)?.toString() ?: ""
                val a = ic?.getTextAfterCursor(2000, 0)?.toString() ?: ""
                txt = b + a
            }
            if (txt.isNullOrEmpty()) {
                Toast.makeText(this, "Kuch likha hi nahi! 😅", Toast.LENGTH_SHORT).show()
                return
            }
            clipMan().setPrimaryClip(ClipData.newPlainText("kb", txt))
            refreshClipPreview()
            Toast.makeText(this, "Copied! 📄", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy fail! 😢", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doPaste() {
        try {
            val t = clipMan().primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrEmpty()) {
                Toast.makeText(this, "Clipboard khali! 📋", Toast.LENGTH_SHORT).show()
                return
            }
            currentInputConnection?.commitText(t, 1)
            updateSugg()
        } catch (e: Exception) {
            Toast.makeText(this, "Paste fail! 😢", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshClipPreview() {
        val pv = clipPreview ?: return
        try {
            val t = clipMan().primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            pv.text = if (t.isNullOrEmpty()) "(khali — COPY dabao!)" else t.take(200)
        } catch (e: Exception) {
            pv.text = "(khali — COPY dabao!)"
        }
    }

    private fun onKey(tag: String) {
        val ic = currentInputConnection ?: return
        when (tag) {
            "SHIFT" -> { isUpper = !isUpper; refreshKeys() }
            "TOGGLE" -> { fancyMode = !fancyMode; refreshKeys() }
            "SYM" -> setMode(1)
            "ABC" -> setMode(0)
            "SYMPAGE" -> setMode(if (viewMode == 2) 1 else 2)
            "EMOJI" -> setMode(3)
            "CLIP" -> setMode(4)
            "HIDE" -> requestHideSelf(0)
            "COPY" -> doCopy()
            "PASTE" -> doPaste()
            "ENTER" -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            "SPACE" -> {
                // double-tap space = ". " ✨
                val now = System.currentTimeMillis()
                if (now - lastSpaceTime < 400) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    lastSpaceTime = 0
                } else {
                    ic.commitText(" ", 1)
                    lastSpaceTime = now
                }
                updateSugg()
            }
            else -> {
                // emoji (2 units) = poora commit! 😀
                if (tag.length > 1) { ic.commitText(tag, 1); updateSugg(); return }
                val ch = tag[0]
                // numbers/symbols = as-is (koi fancy nahi!)
                if (!ch.isLetter()) {
                    ic.commitText(ch.toString(), 1)
                    updateSugg()
                    return
                }
                val out = if (!fancyMode) {
                    if (isUpper) ch.uppercaseChar().toString() else ch.toString()
                } else {
                    val before = ic.getTextBeforeCursor(1, 0)?.toString()
                    val wordStart = before.isNullOrEmpty() || before[0] == ' ' || before[0] == '\n'
                    if (wordStart) boldOf(ch.uppercaseChar())
                    else smallMap[ch.lowercaseChar()]?.toString() ?: ch.toString()
                }
                ic.commitText(out, 1)
                if (isUpper) { isUpper = false; refreshKeys() }
                updateSugg()
            }
        }
    }
}
