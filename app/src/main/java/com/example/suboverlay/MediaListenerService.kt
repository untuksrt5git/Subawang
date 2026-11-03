package com.example.suboverlay

import android.service.notification.NotificationListenerService

/**
 * Service kosong yang hanya bertugas memegang izin "akses notifikasi".
 * Izin itulah yang membuat MediaSessionBridge boleh memanggil
 * getActiveSessions() dan membaca posisi video aplikasi lain.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaSessionBridge.refresh(this)
    }
}
