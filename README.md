# Subtitle Overlay untuk UC Browser (Android)

Aplikasi Android kecil yang menampilkan teks subtitle **mengapung di atas aplikasi lain** —
termasuk UC Browser saat memutar video. UC Browser tidak dimodifikasi sama sekali: subtitle
digambar sebagai *system alert window* (`TYPE_APPLICATION_OVERLAY`) oleh aplikasi kita sendiri,
jadi tetap muncul walau video UC Browser berjalan fullscreen.

```
UC Browser (video)  ──►  lapisan bawah
        ▲
        │  tembus sentuhan (FLAG_NOT_TOUCHABLE)
        │
Subtitle Overlay     ──►  lapisan atas: teks .srt/.vtt + pegangan seret ⠿
```

## Fitur

| Fitur | Keterangan |
|---|---|
| Parser `.srt` & `.vtt` | BOM UTF-8/UTF-16, fallback cp1252, tag HTML/ASS dibersihkan, multi-baris |
| Overlay non-interaktif | `FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE` → kontrol UC Browser tetap bisa disentuh |
| Posisi bisa digeser | Pegangan bulat ⠿ di layar; geser vertikal, posisi disimpan |
| Auto-sync | Membaca posisi video aplikasi lain lewat `MediaSession` (butuh izin akses notifikasi) |
| Jam manual | Play/pause/reset + offset ±0,1 s / ±1 s bila pemutar tidak mempublikasikan posisi |
| Gaya | Ukuran teks, posisi vertikal, tingkat gelap latar |
| Foreground service | Tetap hidup saat pindah ke UC Browser; ada notifikasi dengan tombol jeda/offset/stop |

## Build

Butuh Android Studio (Ladybug/2024.2+) atau JDK 17 + Android SDK 35.

```bash
# dari Android Studio: File → Open → folder ini, lalu Run
# atau lewat CLI:
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Proyek sudah dilengkapi Gradle wrapper (Gradle 8.9), jadi tidak perlu memasang Gradle manual.
Android Studio akan membuat `local.properties` berisi `sdk.dir=…` sendiri saat sync pertama
(berkas itu di-.gitignore, jangan di-commit).

Build sudah diverifikasi bersih di CI lokal: **JDK 17 + Gradle 8.9 + AGP 8.7.3 + compileSdk 35 →
BUILD SUCCESSFUL, tanpa warning Kotlin**, menghasilkan `app-debug.apk` (~5,5 MB).

`minSdk 26` (Android 8) karena `TYPE_APPLICATION_OVERLAY` baru ada sejak API 26.
`targetSdk 35`.

## Cara pakai

1. **Pilih berkas** `.srt`/`.vtt` (boleh dari folder Download).
2. **Izinkan tampil di atas aplikasi lain** → aktifkan *Subtitle Overlay* di daftar
   *Display over other apps*.
3. Opsional, untuk auto-sync: **Akses notifikasi** → aktifkan *Subtitle Overlay — pembaca posisi video*.
4. Tekan **Tampilkan overlay**.
5. Buka video di UC Browser (boleh fullscreen).
6. Sinkronkan:
   - auto-sync aktif → subtitle mengikuti posisi video sendiri;
   - jika tidak tersedia → tekan **Putar jam manual** tepat saat video mulai, lalu koreksi
     dengan `-1s / -0.1s / +0.1s / +1s`.
7. Geser ikon **⠿** untuk memindahkan subtitle (mis. ke atas supaya tidak menutupi kontrol video).

## Kenapa auto-sync kadang tidak tersedia

Android tidak mengizinkan aplikasi lain membaca posisi pemutaran video secara langsung.
Satu-satunya jalur resmi tanpa root adalah `MediaSession`:

- `MediaSessionManager.getActiveSessions()` — hanya boleh dipanggil bila aplikasi memegang
  izin `BIND_NOTIFICATION_LISTENER_SERVICE` (karena itu ada `MediaListenerService` kosong).
- Pemutar harus mempublikasikan `PlaybackState` (ExoPlayer/Media3 biasanya iya, termasuk
  wrapper video milik UC Browser). Kalau tidak ada sesi aktif, aplikasi otomatis memakai jam manual.
- Posisi diinterpolasi dari `PlaybackState.position` + `lastPositionUpdateTime` + `playbackSpeed`,
  jadi tetap halus di antara polling 400 ms.

Pendekatan lain (MediaProjection + OCR frame video) sengaja tidak dipakai: butuh izin rekam layar,
boros baterai, dan tidak akurat untuk teks kecil.

## Struktur

```
app/src/main/java/com/example/suboverlay/
├── MainActivity.kt        # UI: pilih file, izin, offset, gaya
├── OverlayService.kt      # foreground service + WindowManager overlay + ticker 100 ms
├── SrtParser.kt           # parser SRT/VTT + deteksi encoding
├── MediaSessionBridge.kt  # baca posisi video aplikasi lain
├── MediaListenerService.kt# pemegang izin akses notifikasi
└── Prefs.kt               # SharedPreferences (offset, auto-follow, gaya)
app/src/main/res/
├── layout/activity_main.xml
├── values/{strings,colors,themes}.xml
└── drawable/…             # kartu, preview, ikon
```

## Batasan yang perlu diketahui

- **`FLAG_NOT_TOUCHABLE`**: overlay tidak bisa diklik; semua sentuhan diteruskan ke UC Browser.
  Pemindahan posisi dilakukan lewat pegangan ⠿ (window terpisah yang bisa disentuh).
- **Android 12+ (untrusted touch)**: sentuhan dari aplikasi overlay yang *touchable* akan
  ditolak bila menutupi UI bawah; karena itu area subtitle dibuat passthrough, bukan touchable.
- **DRM**: video ber-DRM (Netflix dsb.) tidak bisa ditimpa sama sekali oleh overlay; ini bukan
  kasus UC Browser untuk video web biasa.
- **Baterai/OEM**: beberapa OEM (Xiaomi, Oppo, Vivo) mematikan foreground service; izinkan
  "Autostart"/"Tanpa batas baterai" untuk aplikasi ini.
- Overlay tampil di semua aplikasi, bukan hanya UC Browser — hentikan lewat notifikasi bila selesai.

## Alternatif yang dipertimbangkan

1. **Ekstensi browser (Manifest V3)** — hanya jalan di browser yang mendukung ekstensi
   (Chrome desktop/Edge/Kiwi); UC Browser Android tidak memuat extension Chrome, dan add-on
   UC bersifat tertutup. Tidak dipakai karena tidak bisa diandalkan.
2. **Player ulang** (VLC/MPV dengan `--sub-file`) — paling akurat, tapi tidak menjawab kebutuhan
   "tetap di UC Browser".
3. **Overlay sistem (proyek ini)** — satu-satunya cara yang bekerja di atas UC Browser tanpa root
   dan tanpa memodifikasi aplikasinya.
