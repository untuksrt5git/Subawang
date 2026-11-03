package com.example.suboverlay

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat

/**
 * Membaca posisi pemutaran video milik aplikasi LAIN (mis. UC Browser)
 * lewat MediaSession.
 *
 * Ini satu-satunya cara resmi untuk tahu "detik ke berapa" video yang sedang
 * diputar di aplikasi pihak ketiga tanpa root. Syaratnya aplikasi kita diberi
 * izin "akses notifikasi" (NotificationListenerService). Kalau pemutarnya tidak
 * mempublikasikan MediaSession, otomatis fallback ke jam manual.
 */
object MediaSessionBridge {

    data class Snap(
        val positionMs: Long,
        val anchorElapsedMs: Long,
        val speed: Float,
        val playing: Boolean,
        val app: String?
    ) {
        fun positionAt(nowElapsed: Long): Long {
            if (!playing) return positionMs
            val delta = (nowElapsed - anchorElapsedMs).coerceAtLeast(0L)
            return positionMs + (delta * speed).toLong()
        }
    }

    private val IDLE = Snap(0L, 0L, 1f, false, null)

    @Volatile
    private var snap: Snap = IDLE

    @Volatile
    var available: Boolean = false
        private set

    fun snapshot(): Snap = snap

    fun currentPosition(): Long = snap.positionAt(SystemClock.elapsedRealtime())

    fun hasListenerAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun refresh(ctx: Context) {
        if (!hasListenerAccess(ctx)) {
            available = false
            snap = IDLE
            return
        }

        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            available = false
            snap = IDLE
            return
        }

        val sessions: List<MediaController> = try {
            manager.getActiveSessions(ComponentName(ctx, MediaListenerService::class.java))
        } catch (_: SecurityException) {
            available = false
            snap = IDLE
            return
        } catch (_: Exception) {
            available = false
            snap = IDLE
            return
        }

        var best: Snap? = null
        for (controller in sessions) {
            val state: PlaybackState = controller.playbackState ?: continue
            val playing = state.state == PlaybackState.STATE_PLAYING
            val candidate = Snap(
                positionMs = state.position.coerceAtLeast(0L),
                anchorElapsedMs = if (state.lastPositionUpdateTime > 0L) {
                    state.lastPositionUpdateTime
                } else {
                    SystemClock.elapsedRealtime()
                },
                speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f,
                playing = playing,
                app = controller.packageName
            )
            if (candidate.playing) {
                best = candidate
                break
            }
            if (best == null) best = candidate
        }

        available = best != null
        snap = best ?: IDLE
    }
}
