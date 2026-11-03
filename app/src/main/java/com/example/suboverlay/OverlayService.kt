package com.example.suboverlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.roundToInt

/** Snapshot yang dipakai UI (MainActivity) untuk menampilkan status. */
data class OverlayState(
    val visible: Boolean = false,
    val srtName: String? = null,
    val cueCount: Int = 0,
    val autoFollow: Boolean = false,
    val autoAvailable: Boolean = false,
    val sourceApp: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val offsetMs: Long = 0L,
    val currentText: String = "",
    val status: String = ""
)

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.suboverlay.action.START"
        const val ACTION_STOP = "com.example.suboverlay.action.STOP"
        const val ACTION_RELOAD = "com.example.suboverlay.action.RELOAD"
        const val ACTION_TOGGLE_PLAY = "com.example.suboverlay.action.TOGGLE_PLAY"
        const val ACTION_RESET_CLOCK = "com.example.suboverlay.action.RESET_CLOCK"
        const val ACTION_OFFSET = "com.example.suboverlay.action.OFFSET"
        const val ACTION_RESET_OFFSET = "com.example.suboverlay.action.RESET_OFFSET"
        const val ACTION_APPEARANCE = "com.example.suboverlay.action.APPEARANCE"
        const val EXTRA_DELTA_MS = "delta_ms"

        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 100L
        private const val SESSION_POLL_MS = 400L

        private val _state = MutableStateFlow(OverlayState())
        val state: StateFlow<OverlayState> = _state

        fun start(ctx: Context) {
            val intent = Intent(ctx, OverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun command(ctx: Context, action: String, deltaMs: Long = 0L) {
            val intent = Intent(ctx, OverlayService::class.java).setAction(action)
            if (deltaMs != 0L) intent.putExtra(EXTRA_DELTA_MS, deltaMs)
            try {
                ctx.startService(intent)
            } catch (_: IllegalStateException) {
                // Service tidak aktif; abaikan perintah.
            }
        }
    }

    private lateinit var windowManager: WindowManager

    private var subtitleView: TextView? = null
    private var subtitleParams: WindowManager.LayoutParams? = null
    private var handleView: TextView? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private var cues: List<SrtParser.Cue> = emptyList()
    private var currentText: String = ""
    private var status: String = ""

    private var manualBaseMs: Long = 0L
    private var manualStartedAt: Long = 0L
    private var manualRunning: Boolean = false

    private var lastSessionPoll: Long = 0L
    private var tickerRunning: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        goForeground(manualRunning)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> loadSubtitles()

            ACTION_TOGGLE_PLAY -> togglePlay()

            ACTION_RESET_CLOCK -> {
                manualBaseMs = 0L
                manualStartedAt = SystemClock.elapsedRealtime()
            }

            ACTION_OFFSET -> {
                val delta = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
                Prefs.setOffset(this, Prefs.offsetMs(this) + delta)
            }

            ACTION_RESET_OFFSET -> Prefs.setOffset(this, 0L)

            ACTION_APPEARANCE -> applyAppearance()

            else -> {
                loadSubtitles()
                showOverlay()
            }
        }

        if (!tickerRunning) {
            tickerRunning = true
            handler.post(ticker)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        tickerRunning = false
        removeOverlay()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay

    private fun showOverlay() {
        if (subtitleView != null) return

        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.fontSizeSp(this@OverlayService))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setLineSpacing(dp(2).toFloat(), 1.1f)
            background = pill(Prefs.bgAlpha(this@OverlayService))
            this.text = ""
        }

        val subtitle = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (screenHeight() * Prefs.yFraction(this@OverlayService)).roundToInt()
        }

        val handle = TextView(this).apply {
            this.text = "⠿"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            alpha = 0.55f
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(160, 0, 0, 0))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        val handleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = (subtitle.y - dp(44)).coerceAtLeast(0)
        }

        var downRawY = 0f
        var startY = 0
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startY = subtitle.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val maxY = (screenHeight() - dp(56)).coerceAtLeast(0)
                    subtitle.y = (startY + (event.rawY - downRawY)).roundToInt().coerceIn(0, maxY)
                    windowManager.updateViewLayout(text, subtitle)
                    handleLp.y = (subtitle.y - dp(44)).coerceAtLeast(0)
                    windowManager.updateViewLayout(handle, handleLp)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Prefs.setYFraction(this, subtitle.y.toFloat() / screenHeight().toFloat())
                    view.performClick()
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(text, subtitle)
            windowManager.addView(handle, handleLp)
        } catch (e: Exception) {
            status = "Gagal menampilkan overlay: ${e.message}"
            return
        }

        subtitleView = text
        subtitleParams = subtitle
        handleView = handle
        handleParams = handleLp
    }

    private fun removeOverlay() {
        subtitleView?.let { runCatching { windowManager.removeView(it) } }
        handleView?.let { runCatching { windowManager.removeView(it) } }
        subtitleView = null
        subtitleParams = null
        handleView = null
        handleParams = null
    }

    private fun applyAppearance() {
        val view = subtitleView ?: return
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.fontSizeSp(this))
        view.background = pill(Prefs.bgAlpha(this))
        val lp = subtitleParams ?: return
        lp.y = (screenHeight() * Prefs.yFraction(this)).roundToInt()
        runCatching { windowManager.updateViewLayout(view, lp) }
        handleParams?.let { hp ->
            hp.y = (lp.y - dp(44)).coerceAtLeast(0)
            handleView?.let { runCatching { windowManager.updateViewLayout(it, hp) } }
        }
    }

    // ------------------------------------------------------------------ clock

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSessionPoll >= SESSION_POLL_MS) {
            lastSessionPoll = now
            MediaSessionBridge.refresh(this)
        }

        val autoFollow = Prefs.autoFollow(this) && MediaSessionBridge.available
        val playhead = if (autoFollow) {
            MediaSessionBridge.currentPosition()
        } else {
            manualPosition(now)
        }

        val effective = playhead + Prefs.offsetMs(this)
        val text = cueAt(effective)
        if (text != currentText) {
            currentText = text
            subtitleView?.let { if (it.text.toString() != text) it.text = text }
        }

        publish(playhead, autoFollow)
    }

    private fun manualPosition(now: Long): Long =
        if (manualRunning) manualBaseMs + (now - manualStartedAt) else manualBaseMs

    private fun togglePlay() {
        val now = SystemClock.elapsedRealtime()
        if (manualRunning) {
            manualBaseMs = manualPosition(now)
            manualRunning = false
        } else {
            manualStartedAt = now
            manualRunning = true
        }
        goForeground(manualRunning)
    }

    private fun cueAt(positionMs: Long): String {
        if (cues.isEmpty() || positionMs < 0L) return ""
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return cue.text
            }
        }
        return ""
    }

    private fun publish(playhead: Long, autoFollow: Boolean) {
        val snap = MediaSessionBridge.snapshot()
        _state.value = OverlayState(
            visible = subtitleView != null,
            srtName = Prefs.srtName(this) ?: Prefs.srtPath(this)?.let { File(it).name },
            cueCount = cues.size,
            autoFollow = autoFollow,
            autoAvailable = MediaSessionBridge.available,
            sourceApp = snap.app,
            playing = if (autoFollow) snap.playing else manualRunning,
            positionMs = playhead.coerceAtLeast(0L),
            offsetMs = Prefs.offsetMs(this),
            currentText = currentText,
            status = status
        )
    }

    // ------------------------------------------------------------ subtitles

    private fun loadSubtitles() {
        val path = Prefs.srtPath(this)
        if (path == null) {
            cues = emptyList()
            status = "Belum ada berkas subtitle dipilih."
            return
        }
        val file = File(path)
        if (!file.exists()) {
            cues = emptyList()
            status = "Berkas subtitle tidak ditemukan."
            return
        }
        cues = try {
            SrtParser.parse(SrtParser.decode(file.readBytes()))
        } catch (e: Exception) {
            status = "Gagal membaca subtitle: ${e.message}"
            emptyList()
        }
        status = if (cues.isEmpty()) {
            "Subtitle kosong atau format tidak dikenali."
        } else {
            "${cues.size} cue siap ditampilkan."
        }
        currentText = ""
    }

    // ---------------------------------------------------------- notification

    private fun goForeground(playing: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(playing),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(id: Int, action: String, delta: Long, icon: Int, title: String): NotificationCompat.Action {
            val intent = Intent(this, OverlayService::class.java).setAction(action)
            if (delta != 0L) intent.putExtra(EXTRA_DELTA_MS, delta)
            val pending = PendingIntent.getService(
                this,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action(icon, title, pending)
        }

        val toggle = action(
            1,
            ACTION_TOGGLE_PLAY,
            0L,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Jeda" else "Putar"
        )
        val back = action(2, ACTION_OFFSET, -1000L, android.R.drawable.ic_media_previous, "-1s")
        val forward = action(3, ACTION_OFFSET, 1000L, android.R.drawable.ic_media_next, "+1s")
        val stop = PendingIntent.getService(
            this,
            4,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subtitles)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(currentText.ifBlank { getString(R.string.notif_text_idle) })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(back)
            .addAction(toggle)
            .addAction(forward)
            .addAction(NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop))
            .build()
    }

    private fun shutdown() {
        handler.removeCallbacks(ticker)
        tickerRunning = false
        removeOverlay()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        _state.value = _state.value.copy(visible = false, currentText = "")
        stopSelf()
    }

    // ----------------------------------------------------------------- utils

    private fun screenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.currentWindowMetrics.bounds
            bounds.height()
        } else {
            resources.displayMetrics.heightPixels
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun pill(alpha: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.argb(alpha, 0, 0, 0))
    }
}
