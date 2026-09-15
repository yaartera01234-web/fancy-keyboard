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
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class FancyIME : InputMethodService() {

    private var fancyMode = true   // 👑 ON = Boss style!
    private var isUpper = false    // shift (one-shot)
    private var viewMode = 0       // 0 letters 1 sym1 2 sym2 3 emoji 4 clip
    private var lastSpaceTime = 0L
    private var shiftBtn: Button? = null
    private var toggleBtn: Button? = null
    private var sugViews: List<TextView> = emptyList()
    private var clipPreview: TextView? = null

    // Personal word learning stays local to this phone. Password fields are ignored.
    private val wordPrefs by lazy { getSharedPreferences("fancy_keyboard_words", Context.MODE_PRIVATE) }
    private val learnedWords: MutableMap<String, Int> by lazy { loadLearnedWords() }

    // Emoji catalog/navigation state. The catalog itself is bundled in EmojiCatalog.kt.
    private var emojiCategoryIndex = 0
    private var emojiPage = 0
    private var emojiGrid: GridLayout? = null
    private var emojiPageLabel: TextView? = null
    private val emojiPageSize = 48
    private val maxLearnedWords = 5000
    private val emojiPrefs by lazy { getSharedPreferences("fancy_keyboard_emoji", Context.MODE_PRIVATE) }

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
        "welcome", "maza", "bhai", "dost", "khabar", "sunao", "bolo",
        // Common completions so they work before the personal dictionary learns them.
        "efficient", "efficiency", "effective", "effectively", "important",
        "beautiful", "different", "because", "something", "working",
        "download", "keyboard", "suggestion", "suggestions", "message",
        "friend", "friends", "today", "tomorrow", "always", "really",
        "please", "already", "available", "correct", "change", "changing"
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
        if (layoutId == R.layout.emoji_view) setupEmojiView(v)
        val all = mutableListOf<Button>()
        collectButtons(v, all)
        for (b in all) bindButton(b)
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

    private fun bindButton(b: Button) {
        val tag = b.tag as? String ?: return
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun emojiGroups(): List<EmojiCategory> {
        val recent = readRecentEmoji()
        val recentItems = if (recent.isEmpty()) {
            listOf("😀", "😂", "❤️", "🤣", "😍", "🥰", "😊", "👍", "🙏", "🔥", "🥳", "😭", "👏", "😎", "✨", "💯")
        } else recent
        return listOf(EmojiCategory("Recently used", "🕘", recentItems)) + EmojiCatalog.categories
    }

    private fun setupEmojiView(v: View) {
        emojiGrid = v.findViewById(R.id.emoji_grid)
        emojiPageLabel = v.findViewById(R.id.emoji_page_label)
        val categoryBar: LinearLayout? = v.findViewById(R.id.emoji_categories)
        categoryBar?.removeAllViews()
        val groups = emojiGroups()
        groups.forEachIndexed { index, group ->
            val button = Button(this, null, 0, R.style.KbTool)
            button.tag = "EMOJI_CAT:$index"
            button.text = group.icon
            button.contentDescription = group.title
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            button.layoutParams = LinearLayout.LayoutParams(dp(48), dp(38)).apply {
                setMargins(dp(2), dp(1), dp(2), dp(1))
            }
            categoryBar?.addView(button)
        }
        renderEmojiPage()
    }

    private fun renderEmojiPage() {
        val grid = emojiGrid ?: return
        val label = emojiPageLabel ?: return
        val groups = emojiGroups()
        if (groups.isEmpty()) return
        emojiCategoryIndex = emojiCategoryIndex.coerceIn(0, groups.lastIndex)
        val group = groups[emojiCategoryIndex]
        val items = group.emojis.ifEmpty { listOf("😀") }
        val pageCount = ((items.size + emojiPageSize - 1) / emojiPageSize).coerceAtLeast(1)
        emojiPage = emojiPage.coerceIn(0, pageCount - 1)
        val from = emojiPage * emojiPageSize
        val to = minOf(from + emojiPageSize, items.size)
        grid.removeAllViews()
        items.subList(from, to).forEach { emoji ->
            val button = Button(this, null, 0, R.style.KbEmoji)
            button.tag = "EMOJI_ITEM:$emoji"
            button.text = emoji
            button.contentDescription = "Emoji $emoji"
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.height = dp(48)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            button.layoutParams = lp
            grid.addView(button)
            bindButton(button)
        }
        label.text = "${group.icon} ${emojiPage + 1}/$pageCount"
    }

    private fun readRecentEmoji(): List<String> {
        val result = mutableListOf<String>()
        try {
            val array = JSONArray(emojiPrefs.getString("recent", "[]") ?: "[]")
            for (i in 0 until array.length()) result.add(array.optString(i))
        } catch (_: Exception) {
        }
        return result.filter { it.isNotEmpty() }.take(30)
    }

    private fun rememberEmoji(emoji: String) {
        val recent = mutableListOf(emoji)
        recent.addAll(readRecentEmoji().filter { it != emoji })
        try {
            val array = JSONArray()
            recent.take(30).forEach { array.put(it) }
            emojiPrefs.edit().putString("recent", array.toString()).apply()
        } catch (_: Exception) {
        }
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

    override fun onFinishInput() {
        learnCurrentWord()
        super.onFinishInput()
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

    private fun learningAllowed(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME) return false
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun normalWord(word: String): String {
        return word.trim().lowercase(Locale.ROOT).filter { it.isLetter() }
    }

    private fun loadLearnedWords(): MutableMap<String, Int> {
        val result = mutableMapOf<String, Int>()
        try {
            val json = JSONObject(wordPrefs.getString("words", "{}") ?: "{}")
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val count = json.optInt(key, 0)
                if (key.length >= 2 && count > 0) result[key] = count
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun saveLearnedWords() {
        try {
            val json = JSONObject()
            learnedWords.forEach { (word, count) -> json.put(word, count) }
            wordPrefs.edit().putString("words", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun learnWord(word: String) {
        if (!learningAllowed()) return
        val clean = normalWord(word)
        if (clean.length < 2 || clean.length > 32 || clean.any { it.isDigit() }) return
        learnedWords[clean] = ((learnedWords[clean] ?: 0) + 1).coerceAtMost(999)
        if (learnedWords.size > maxLearnedWords) {
            val keep = learnedWords.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(maxLearnedWords)
                .associate { it.key to it.value }
            learnedWords.clear()
            learnedWords.putAll(keep)
        }
        saveLearnedWords()
    }

    private fun learnCurrentWord() = learnWord(currentWord().first)

    private fun rankedCompletions(prefix: String): List<String> {
        return (WORDS + learnedWords.keys)
            .asSequence()
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
            .filter { it.startsWith(prefix) && it != prefix }
            .sortedWith(
                compareByDescending<String> { learnedWords[it] ?: 0 }
                    .thenBy { it.length }
                    .thenBy { it }
            )
            .toList()
    }

    private fun updateSugg() {
        if (sugViews.size < 3) return
        val (plain, _) = currentWord()
        val list: List<String> = if (plain.isEmpty()) {
            val learned = learnedWords.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            (learned + listOf("han", "acha", "ok", "boss", "haha")).distinct().take(3)
        } else {
            val out = mutableListOf(plain)
            out.addAll(rankedCompletions(plain).take(2))
            for (d in listOf("han", "acha", "ok", "boss", "haha")) {
                if (out.size >= 3) break
                if (!out.contains(d)) out.add(d)
            }
            out
        }
        for (i in 0..2) sugViews[i].text = list.getOrElse(i) { "" }
    }

    private fun pickSugg(w: String) {
        val ic = currentInputConnection ?: return
        val (plain, units) = currentWord()
        if (w.lowercase(Locale.ROOT) != plain) learnWord(w)
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
        when {
            tag.startsWith("EMOJI_CAT:") -> {
                emojiCategoryIndex = tag.substringAfter(":").toIntOrNull() ?: 0
                emojiPage = 0
                renderEmojiPage()
            }
            tag == "EMOJI_PREV" -> {
                emojiPage--
                renderEmojiPage()
            }
            tag == "EMOJI_NEXT" -> {
                emojiPage++
                renderEmojiPage()
            }
            tag.startsWith("EMOJI_ITEM:") -> {
                val emoji = tag.removePrefix("EMOJI_ITEM:")
                rememberEmoji(emoji)
                ic.commitText(emoji, 1)
                updateSugg()
            }
            tag == "SHIFT" -> { isUpper = !isUpper; refreshKeys() }
            tag == "TOGGLE" -> { fancyMode = !fancyMode; refreshKeys() }
            tag == "SYM" -> setMode(1)
            tag == "ABC" -> setMode(0)
            tag == "SYMPAGE" -> setMode(if (viewMode == 2) 1 else 2)
            tag == "EMOJI" -> setMode(3)
            tag == "CLIP" -> setMode(4)
            tag == "HIDE" -> requestHideSelf(0)
            tag == "COPY" -> doCopy()
            tag == "PASTE" -> doPaste()
            tag == "ENTER" -> {
                learnCurrentWord()
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            tag == "SPACE" -> {
                learnCurrentWord()
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
                if (tag.length > 1) { learnCurrentWord(); ic.commitText(tag, 1); updateSugg(); return }
                val ch = tag[0]
                // numbers/symbols = as-is (koi fancy nahi!)
                if (!ch.isLetter()) {
                    learnCurrentWord()
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
