package com.internetdeco.decocontrol

import android.content.*
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.*
import android.view.inputmethod.*
import com.jcraft.jsch.ChannelShell
import com.termux.terminal.*
import com.termux.view.TerminalRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

object TerminalHub {
    private val sessions = ConcurrentHashMap<String, RemoteTerminal>()
    fun get(context: Context, host: String) = sessions.getOrPut(host) { RemoteTerminal(context.applicationContext, host) }
    fun isOpen(host: String) = sessions[host]?.ui?.value?.open == true
    fun close(host: String) { sessions[host]?.stop() }
    fun remove(host: String) { sessions.remove(host)?.dispose() }
    fun removeMatchingPrefix(prefix: String) { sessions.keys.filter { it.startsWith(prefix) }.forEach(::remove) }
}

data class TerminalStatus(
    val open: Boolean = false, val connecting: Boolean = false, val error: String? = null,
    val title: String = "", val ctrl: Boolean = false, val fontSp: Float = 14f,
)

class RemoteTerminal(private val context: Context, val host: String) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), { Thread(it, "deco-terminal-reader").apply { isDaemon = true } })
    private val writer = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), { Thread(it, "deco-terminal-writer").apply { isDaemon = true } })
    private val pending = Semaphore(8)
    private val generation = AtomicLong()
    private var task: Future<*>? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var output: OutputStream? = null
    private val state = MutableStateFlow(TerminalStatus(title = "root@$host", fontSp = Controller.vault.get("terminal_font:$host").toFloatOrNull()?.coerceIn(9f, 24f) ?: 14f))
    val ui = state.asStateFlow()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.forEach { it() } }
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession?) { changed() }
        override fun onTitleChanged(s: TerminalSession?) { changed() }
        override fun onSessionFinished(s: TerminalSession?) { state.update { it.copy(open = false) }; changed() }
        override fun onCopyTextToClipboard(s: TerminalSession?, text: String?) { copy(text.orEmpty()) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { paste() }
        override fun onBell(s: TerminalSession?) { changed() }
        override fun onColorsChanged(s: TerminalSession?) { changed() }
        override fun onTerminalCursorStateChange(visible: Boolean) { changed() }
        override fun getTerminalCursorStyle() = 0
        override fun logError(tag: String?, message: String?) { Log.e("DecoTerminal", "Terminal emulator error") }
        override fun logWarn(tag: String?, message: String?) { Log.w("DecoTerminal", "Terminal emulator warning") }
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e("DecoTerminal", "Terminal emulator error") }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e("DecoTerminal", e?.javaClass?.simpleName ?: "Terminal emulator error") }
    }
    val emulator = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) { send(data.copyOfRange(offset, offset + count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) { state.update { it.copy(title = newTitle ?: "root@$host") }; changed() }
        override fun onCopyTextToClipboard(text: String?) { copy(text.orEmpty()) }
        override fun onPasteTextFromClipboard() { paste() }
        override fun onBell() { changed() }
        override fun onColorsChanged() { changed() }
    }, 80, 24, 8, 16, 5000, client)

    @Synchronized fun start() {
        if (state.value.open || state.value.connecting || worker.isShutdown) return
        val id = generation.incrementAndGet()
        state.update { it.copy(connecting = true, error = null, ctrl = false) }
        task = worker.submit {
            var current: ChannelShell? = null
            try {
                val c = Controller.bridge.shell(host, emulator.mColumns, emulator.mRows)
                current = c
                if (generation.get() != id) return@submit
                channel = c; output = c.outputStream
                val input = c.inputStream
                c.connect(Controller.connectionOptions().channelTimeoutMs)
                c.setPtySize(emulator.mColumns, emulator.mRows, 0, 0)
                if (generation.get() != id) return@submit
                state.update { it.copy(open = true, connecting = false, error = null) }
                val buffer = ByteArray(8192)
                while (c.isConnected && generation.get() == id) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val data = buffer.copyOf(n)
                    pending.acquire()
                    main.post { try { if (generation.get() == id) { emulator.append(data, data.size); changed() } } finally { pending.release() } }
                }
            } catch (e: Exception) {
                if (generation.get() == id) state.update { it.copy(error = e.message ?: "SSH session closed") }
            } finally {
                current?.disconnect()
                if (generation.get() == id) {
                    channel = null; output = null
                    state.update { it.copy(open = false, connecting = false, ctrl = false) }
                }
            }
        }
    }
    fun toggleCtrl() { state.update { it.copy(ctrl = !it.ctrl) } }
    fun setFont(sp: Float, persist: Boolean = true) {
        val value = sp.coerceIn(9f, 24f)
        state.update { it.copy(fontSp = value) }
        if (persist) Controller.vault.put("terminal_font:$host", value.toString())
        changed()
    }
    fun send(bytes: ByteArray) {
        val id = generation.get()
        if (!state.value.open || writer.isShutdown) return
        writer.execute {
            if (generation.get() != id || !state.value.open) return@execute
            val target = channel; val stream = output
            try { stream?.write(bytes); stream?.flush() }
            catch (e: Exception) { target?.disconnect(); if (generation.get() == id) state.update { it.copy(error = "Input was not delivered. Reconnect before sending another command.", open = false) } }
        }
    }
    fun text(value: String) {
        var data = value
        if (state.value.ctrl && value.isNotEmpty()) {
            val c = value.first().uppercaseChar().code
            if (c in 64..95) data = (c and 31).toChar() + value.drop(1)
            state.update { it.copy(ctrl = false) }
        }
        send(data.toByteArray())
    }
    fun key(code: Int) { KeyHandler.getCode(code, if (state.value.ctrl) KeyHandler.KEYMOD_CTRL else 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)?.let { send(it.toByteArray()) }; state.update { it.copy(ctrl = false) } }
    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        if (columns < 10 || rows < 3) return
        emulator.resize(columns, rows, cellWidth, cellHeight)
        if (!writer.isShutdown) writer.execute { runCatching { channel?.setPtySize(columns, rows, 0, 0) } }
    }
    fun transcript(): String = emulator.screen.transcriptText
    fun copy(text: String = transcript()) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SSH $host", text))
        Controller.toast("Terminal text copied")
    }
    fun paste() {
        if (!state.value.open) { Controller.toast("Connect the terminal before pasting"); return }
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        if (clip != null && clip.itemCount > 0) {
            val value = clip.getItemAt(0).coerceToText(context).toString()
            if (value.length <= 65536) emulator.paste(value) else Controller.toast("Paste up to 64 KiB at once")
        }
    }
    @Synchronized fun stop() {
        generation.incrementAndGet(); channel?.disconnect(); channel = null; output = null; task?.cancel(true); writer.queue.clear()
        state.update { it.copy(open = false, connecting = false, ctrl = false) }
    }
    fun dispose() { stop(); worker.shutdownNow(); writer.shutdownNow(); listeners.clear() }
}

class RemoteTerminalView(context: Context, val remote: RemoteTerminal) : View(context) {
    private var size = (remote.ui.value.fontSp * resources.displayMetrics.scaledDensity).toInt()
    private var renderer = TerminalRenderer(size, Typeface.MONOSPACE)
    private var fontWidth = 1f; private var lineHeight = 1; private var topInset = 0; private var top = 0
    private var lastY = 0f; private var moved = false; private var pinched = false; private var longPressed = false
    var onSelectText: (() -> Unit)? = null
    private val redraw: () -> Unit = { post { val scrolled=remote.emulator.scrollCounter;if(top<0)top-=scrolled;remote.emulator.clearScrollCounter();syncFont(); invalidate() } }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinched = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean { remote.setFont(remote.ui.value.fontSp * detector.scaleFactor, persist = false); syncFont(); return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { remote.setFont(remote.ui.value.fontSp) }
    })
    init {
        isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(0xff0a0f16.toInt())
        contentDescription = "SSH terminal ${remote.host}. Tap for keyboard, pinch to zoom, swipe to scroll."
        setOnLongClickListener { longPressed = true; onSelectText?.invoke(); true }
        updateFont()
    }
    private fun updateFont() {
        val p = Paint().apply { typeface = Typeface.MONOSPACE; textSize = size.toFloat() }
        fontWidth = p.measureText("X"); lineHeight = kotlin.math.ceil(p.fontSpacing.toDouble()).toInt()
        topInset = lineHeight + kotlin.math.ceil(p.ascent().toDouble()).toInt()
        renderer = TerminalRenderer(size, Typeface.MONOSPACE)
    }
    private fun syncFont() {
        val wanted = (remote.ui.value.fontSp * resources.displayMetrics.scaledDensity).toInt()
        if (wanted != size) { size = wanted; updateFont(); resize() }
    }
    private fun resize() { if (width > 0 && height > 0) remote.resize((width / fontWidth).toInt().coerceAtLeast(10), ((height - topInset) / lineHeight).coerceAtLeast(3), fontWidth.toInt(), lineHeight) }
    fun keyboard(show: Boolean) { requestFocus(); val ime = context.getSystemService(InputMethodManager::class.java); if (show) ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) else ime.hideSoftInputFromWindow(windowToken, 0) }
    fun scrollToBottom() { top = 0; invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { resize() }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); top = top.coerceIn(-remote.emulator.screen.activeTranscriptRows, 0); renderer.render(remote.emulator, canvas, top, -1, -1, -1, -1) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); remote.addListener(redraw) }
    override fun onDetachedFromWindow() { remote.removeListener(redraw); super.onDetachedFromWindow() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY = event.y; moved = false; pinched = false; longPressed = false; parent.requestDisallowInterceptTouchEvent(true); super.onTouchEvent(event) }
            MotionEvent.ACTION_POINTER_DOWN -> { pinched = true; cancelLongPress() }
            MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && !pinched) {
                val delta = ((event.y - lastY) / lineHeight).toInt()
                if (delta != 0) { top = (top - delta).coerceIn(-remote.emulator.screen.activeTranscriptRows, 0); lastY = event.y; moved = true; cancelLongPress(); invalidate() }
            }
            MotionEvent.ACTION_UP -> { isPressed = false; cancelLongPress(); parent.requestDisallowInterceptTouchEvent(false); if (!moved && !pinched && !longPressed) { performClick(); keyboard(true) } }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; cancelLongPress(); parent.requestDisallowInterceptTouchEvent(false); moved = false; pinched = false }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { top = 0; remote.text(text?.toString().orEmpty()); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { repeat(beforeLength.coerceAtMost(256)) { remote.send(byteArrayOf(127)) }; return true }
            override fun sendKeyEvent(event: KeyEvent): Boolean = onKeyDown(event.keyCode, event)
            override fun performEditorAction(actionCode: Int): Boolean { remote.send(byteArrayOf(13)); return true }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val modifiers = (if (event.isCtrlPressed) KeyHandler.KEYMOD_CTRL else 0) or (if (event.isAltPressed) KeyHandler.KEYMOD_ALT else 0) or (if (event.isShiftPressed) KeyHandler.KEYMOD_SHIFT else 0)
        val code = KeyHandler.getCode(keyCode, modifiers, remote.emulator.isCursorKeysApplicationMode, remote.emulator.isKeypadApplicationMode)
        if (code != null) { remote.send(code.toByteArray()); top = 0; return true }
        val char = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
        if (char != 0) { if (event.isCtrlPressed && char in 32..127) remote.send(byteArrayOf((char and 31).toByte())) else remote.text(String(Character.toChars(char))); top = 0; return true }
        return super.onKeyDown(keyCode, event)
    }
}
