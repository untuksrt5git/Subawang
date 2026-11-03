package com.example.suboverlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvFile: TextView
    private lateinit var tvOverlayPerm: TextView
    private lateinit var tvNotifAccess: TextView
    private lateinit var btnToggleOverlay: Button
    private lateinit var tvNow: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvOffset: TextView
    private lateinit var tvSource: TextView
    private lateinit var cbAuto: CheckBox
    private lateinit var btnPlayPause: Button
    private lateinit var tvFontValue: TextView
    private lateinit var tvYValue: TextView
    private lateinit var tvAlphaValue: TextView
    private lateinit var sbFont: SeekBar
    private lateinit var sbY: SeekBar
    private lateinit var sbAlpha: SeekBar

    private var overlayVisible = false
    private var syncingUi = false

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importSubtitle(uri)
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        wireActions()
        setupSeekBars()
        observeServiceState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshFileInfo()
    }

    // ------------------------------------------------------------------ views

    private fun bindViews() {
        tvFile = findViewById(R.id.tvFile)
        tvOverlayPerm = findViewById(R.id.tvOverlayPerm)
        tvNotifAccess = findViewById(R.id.tvNotifAccess)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        tvNow = findViewById(R.id.tvNow)
        tvPosition = findViewById(R.id.tvPosition)
        tvOffset = findViewById(R.id.tvOffset)
        tvSource = findViewById(R.id.tvSource)
        cbAuto = findViewById(R.id.cbAuto)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        tvFontValue = findViewById(R.id.tvFontValue)
        tvYValue = findViewById(R.id.tvYValue)
        tvAlphaValue = findViewById(R.id.tvAlphaValue)
        sbFont = findViewById(R.id.sbFont)
        sbY = findViewById(R.id.sbY)
        sbAlpha = findViewById(R.id.sbAlpha)
    }

    private fun wireActions() {
        findViewById<Button>(R.id.btnPick).setOnClickListener { pickSubtitle.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener { openNotificationAccessSettings() }
        findViewById<Button>(R.id.btnToggleOverlay).setOnClickListener { toggleOverlay() }

        findViewById<Button>(R.id.btnMinus1000).setOnClickListener { changeOffset(-1000L) }
        findViewById<Button>(R.id.btnMinus100).setOnClickListener { changeOffset(-100L) }
        findViewById<Button>(R.id.btnPlus100).setOnClickListener { changeOffset(100L) }
        findViewById<Button>(R.id.btnPlus1000).setOnClickListener { changeOffset(1000L) }
        findViewById<Button>(R.id.btnResetOffset).setOnClickListener {
            Prefs.setOffset(this, 0L)
            OverlayService.command(this, OverlayService.ACTION_RESET_OFFSET)
        }

        btnPlayPause.setOnClickListener { OverlayService.command(this, OverlayService.ACTION_TOGGLE_PLAY) }
        findViewById<Button>(R.id.btnResetClock).setOnClickListener {
            OverlayService.command(this, OverlayService.ACTION_RESET_CLOCK)
        }

        cbAuto.setOnCheckedChangeListener { _, checked ->
            if (syncingUi) return@setOnCheckedChangeListener
            Prefs.setAutoFollow(this, checked)
            if (checked && !MediaSessionBridge.hasListenerAccess(this)) {
                Toast.makeText(this, R.string.toast_need_notif_access, Toast.LENGTH_LONG).show()
                openNotificationAccessSettings()
            }
        }
    }

    private fun setupSeekBars() {
        sbFont.max = 28
        sbFont.progress = (Prefs.fontSizeSp(this) - 12f).toInt().coerceIn(0, 28)
        sbFont.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setFontSizeSp(this@MainActivity, 12f + progress)
                tvFontValue.text = "${12 + progress}sp"
                OverlayService.command(this@MainActivity, OverlayService.ACTION_APPEARANCE)
            }
        })

        sbY.max = 92
        sbY.progress = (Prefs.yFraction(this) * 100f).toInt().coerceIn(0, 92)
        sbY.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setYFraction(this@MainActivity, progress / 100f)
                tvYValue.text = "$progress%"
                OverlayService.command(this@MainActivity, OverlayService.ACTION_APPEARANCE)
            }
        })

        sbAlpha.max = 255
        sbAlpha.progress = Prefs.bgAlpha(this)
        sbAlpha.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setBgAlpha(this@MainActivity, progress)
                tvAlphaValue.text = "$progress"
                OverlayService.command(this@MainActivity, OverlayService.ACTION_APPEARANCE)
            }
        })

        tvFontValue.text = "${Prefs.fontSizeSp(this).toInt()}sp"
        tvYValue.text = "${(Prefs.yFraction(this) * 100).toInt()}%"
        tvAlphaValue.text = "${Prefs.bgAlpha(this)}"
    }

    // ---------------------------------------------------------------- actions

    private fun importSubtitle(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(filesDir, "subtitles").apply { mkdirs() }
                    val target = File(dir, "current_subtitle")
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    val parsed = SrtParser.parse(SrtParser.decode(target.readBytes()))
                    if (parsed.isEmpty()) return@runCatching null
                    Prefs.setSrtPath(this@MainActivity, target.absolutePath)
                    parsed.size
                }.getOrNull()
            }

            if (result == null) {
                Toast.makeText(this@MainActivity, R.string.toast_parse_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            tvFile.text = getString(R.string.file_info, File(Prefs.srtPath(this@MainActivity)!!).name, result)
            OverlayService.command(this@MainActivity, OverlayService.ACTION_RELOAD)
        }
    }

    private fun toggleOverlay() {
        if (overlayVisible) {
            OverlayService.command(this, OverlayService.ACTION_STOP)
            overlayVisible = false
            renderToggleButton(false)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (Prefs.srtPath(this) == null) {
            Toast.makeText(this, R.string.toast_pick_file_first, Toast.LENGTH_LONG).show()
            return
        }
        askNotificationPermission()
        OverlayService.start(this)
        overlayVisible = true
        renderToggleButton(true)
    }

    private fun changeOffset(deltaMs: Long) {
        val value = Prefs.offsetMs(this) + deltaMs
        Prefs.setOffset(this, value)
        OverlayService.command(this, OverlayService.ACTION_OFFSET, deltaMs)
        tvOffset.text = formatOffset(value)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            refreshPermissionStatus()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------- ui

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                OverlayService.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: OverlayState) {
        overlayVisible = state.visible
        renderToggleButton(state.visible)

        tvNow.text = state.currentText.ifBlank { getString(R.string.now_empty) }
        tvPosition.text = formatTime(state.positionMs)
        tvOffset.text = formatOffset(state.offsetMs)

        val auto = Prefs.autoFollow(this) && state.autoAvailable
        tvSource.text = when {
            !Prefs.autoFollow(this) -> getString(R.string.source_manual)
            auto -> getString(R.string.source_auto, state.sourceApp ?: "-")
            else -> getString(R.string.source_auto_missing)
        }

        syncingUi = true
        cbAuto.isChecked = Prefs.autoFollow(this)
        syncingUi = false

        btnPlayPause.isEnabled = !auto
        btnPlayPause.text = getString(
            if (state.playing && !auto) R.string.btn_pause else R.string.btn_play
        )

        val srtName = state.srtName
        if (srtName != null && state.cueCount > 0) {
            tvFile.text = getString(R.string.file_info, srtName, state.cueCount)
        }
    }

    private fun renderToggleButton(visible: Boolean) {
        btnToggleOverlay.setText(if (visible) R.string.btn_hide_overlay else R.string.btn_show_overlay)
    }

    private fun refreshPermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlayPerm.setText(if (overlayOk) R.string.perm_granted else R.string.perm_missing)
        tvOverlayPerm.setTextColor(
            ContextCompat.getColor(this, if (overlayOk) R.color.ok else R.color.warn)
        )

        val notifOk = MediaSessionBridge.hasListenerAccess(this)
        tvNotifAccess.setText(if (notifOk) R.string.perm_granted else R.string.perm_missing)
        tvNotifAccess.setTextColor(
            ContextCompat.getColor(this, if (notifOk) R.color.ok else R.color.warn)
        )
    }

    private fun refreshFileInfo() {
        val path = Prefs.srtPath(this)
        tvFile.text = if (path == null) {
            getString(R.string.file_none)
        } else {
            getString(R.string.file_info_no_count, File(path).name)
        }
        tvPosition.text = formatTime(OverlayService.state.value.positionMs)
        tvOffset.text = formatOffset(Prefs.offsetMs(this))
    }

    // ---------------------------------------------------------------- format

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSeconds = safe / 1000
        return String.format(
            Locale.US,
            "%02d:%02d.%d",
            totalSeconds / 60,
            totalSeconds % 60,
            (safe % 1000) / 100
        )
    }

    private fun formatOffset(ms: Long): String {
        val sign = if (ms > 0) "+" else ""
        return String.format(Locale.US, "offset %s%.1fs", sign, ms / 1000.0)
    }

    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
    }
}
