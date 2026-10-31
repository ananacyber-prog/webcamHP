# Webcam Vertical Recorder (Android)

Rekam video dari webcam UVC (via OTG USB-C) + mic eksternal USB, output MP4 vertikal (mis. 720x1280, H.264 + AAC).
Hasil disimpan di Movies/WebcamVertical.

## Cara dapat file .apk (tanpa install Android Studio)
1. Buat repo baru di GitHub, upload seluruh isi folder ini (termasuk folder `.github`).
2. Buka tab **Actions** -> workflow **Build APK** -> tunggu hijau (±3-5 menit).
   (Bisa juga klik **Run workflow** manual.)
3. Klik run tersebut -> bagian **Artifacts** -> unduh **WebcamVertical-APK** (zip berisi app-debug.apk).
4. Kirim app-debug.apk ke HP, izinkan "Install dari sumber tidak dikenal", lalu install.

## Atau build lokal
Android Studio -> Open folder ini -> Build > Build APK(s).
Atau CLI: `gradle assembleDebug` (JDK 17 + Android SDK 34).
APK: app/build/outputs/apk/debug/app-debug.apk

## Pemakaian
1. Colok webcam + mic USB (pakai hub OTG bila perlu), buka aplikasi, izinkan Kamera/Mikrofon/USB.
2. Tombol "Putar 90°/270°" menentukan arah putar gambar (cocokkan dengan posisi webcam).
3. Tombol Mic: ganti sumber audio (USB diprioritaskan otomatis).
4. REKAM / STOP.
