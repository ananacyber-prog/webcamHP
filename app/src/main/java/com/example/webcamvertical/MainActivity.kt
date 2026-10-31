package com.example.webcamvertical

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import kotlin.math.min

class MainActivity : Activity() {

    private var helper: ICameraHelper? = null
    private lateinit var container: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private lateinit var btnRec: Button
    private lateinit var btnRot: Button
    private lateinit var btnMic: Button

    private var previewSurface: Surface? = null
    private var surfaceAdded = false
    @Volatile private var cameraOpen = false
    private var camW = 0
    private var camH = 0
    private var rotation = 90            // 90 = searah jarum jam, 270 = berlawanan
    @Volatile private var recorder: VideoRecorder? = null
    private var recStartMs = 0L
    private var selectedMicId: Int? = null

    private val ui = Handler(Looper.getMainLooper())
    private val timer = object : Runnable {
        override fun run() {
            if (recorder != null) {
                val s = (System.currentTimeMillis() - recStartMs) / 1000
                status.text = "● REC %02d:%02d   mic: %s".format(s / 60, s % 60, recorder?.micName)
                ui.postDelayed(this, 500)
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        requestNeededPermissions()

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { refreshMicLabel() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { refreshMicLabel() }
        }, ui)
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) initHelper()
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
        releaseHelper()
    }

    // ------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))

        texture = TextureView(this)
        container.addView(texture, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                previewSurface = Surface(st)
                addPreviewSurface()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                try { previewSurface?.let { helper?.removeSurface(it) } } catch (_: Exception) {}
                previewSurface?.release()
                previewSurface = null
                surfaceAdded = false
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 14f
            text = "Colokkan webcam USB lewat OTG…"
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        btnRot = Button(this).apply {
            text = "Putar 90°"
            setOnClickListener {
                if (recorder != null) return@setOnClickListener
                rotation = if (rotation == 90) 270 else 90
                text = "Putar $rotation°"
                layoutPreview()
            }
        }
        btnRec = Button(this).apply {
            text = "● REKAM"
            setOnClickListener {
                if (recorder == null) startRecording() else stopRecording()
            }
        }
        btnMic = Button(this).apply {
            setOnClickListener { cycleMic() }
        }
        val lp = { LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) } }
        bar.addView(btnRot, lp())
        bar.addView(btnRec, lp())
        bar.addView(btnMic, lp())
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        refreshMicLabel()
    }

    /** Preview diputar sesuai hasil rekaman (vertikal) dan dimuat penuh di layar. */
    private fun layoutPreview() {
        runOnUiThread {
            if (camW == 0 || camH == 0 || container.width == 0) return@runOnUiThread
            val scale = min(container.width / camH.toFloat(), container.height / camW.toFloat())
            val lp = FrameLayout.LayoutParams((camW * scale).toInt(), (camH * scale).toInt(), Gravity.CENTER)
            texture.layoutParams = lp
            texture.rotation = rotation.toFloat()
        }
    }

    // ------------------------------------------------------------ permissions

    private fun neededPermissions(): Array<String> {
        val l = arrayListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return l.toTypedArray()
    }

    private fun hasPermissions() = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        if (!hasPermissions()) requestPermissions(neededPermissions(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasPermissions()) initHelper()
        else Toast.makeText(this, "Izin kamera, mikrofon & penyimpanan dibutuhkan", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ UVC camera

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            helper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val h = helper ?: return
            // Pilih resolusi 16:9 terbesar sampai 1280x720
            val sizes = h.getSupportedSizeList() ?: emptyList()
            val best = sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1280 }.maxByOrNull { it.width }
                ?: sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
                ?: sizes.firstOrNull()
            if (best != null) h.setPreviewSize(best)

            h.startPreview()
            val size = h.getPreviewSize()
            if (size != null) { camW = size.width; camH = size.height }

            h.setFrameCallback(IFrameCallback { frame -> recorder?.onFrame(frame) }, UVCCamera.PIXEL_FORMAT_NV21)

            cameraOpen = true
            runOnUiThread {
                status.text = "Kamera siap ${camW}x${camH} → output ${camH}x${camW} (vertikal)"
                layoutPreview()
                addPreviewSurface()
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            cameraOpen = false
            surfaceAdded = false
            runOnUiThread {
                stopRecording()
                status.text = "Kamera terputus"
            }
        }

        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {
            runOnUiThread { status.text = "Webcam dicabut" }
        }
        override fun onCancel(device: UsbDevice) {
            runOnUiThread { status.text = "Izin USB ditolak" }
        }
    }

    private fun initHelper() {
        if (helper != null) return
        helper = CameraHelper().also { it.setStateCallback(stateCallback) }
    }

    private fun releaseHelper() {
        cameraOpen = false
        surfaceAdded = false
        try { helper?.release() } catch (_: Exception) {}
        helper = null
    }

    private fun addPreviewSurface() {
        val s = previewSurface ?: return
        if (cameraOpen && !surfaceAdded) {
            helper?.addSurface(s, false)
            surfaceAdded = true
        }
    }

    // ------------------------------------------------------------ microphone

    private fun inputMics(): List<AudioDeviceInfo> {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).sortedBy {
            if (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) 0 else 1
        }
    }

    private fun currentMic(): AudioDeviceInfo? {
        val list = inputMics()
        return selectedMicId?.let { id -> list.find { it.id == id } } ?: list.firstOrNull()
    }

    private fun micLabel(d: AudioDeviceInfo?): String {
        if (d == null) return "Mic: -"
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "HP"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Kabel"
            else -> "Lain"
        }
        return "Mic: $kind\n${d.productName}"
    }

    private fun refreshMicLabel() {
        if (!::btnMic.isInitialized) return
        selectedMicId = null // pilih ulang otomatis (USB diprioritaskan)
        btnMic.text = micLabel(currentMic())
    }

    private fun cycleMic() {
        if (recorder != null) return
        val list = inputMics()
        if (list.isEmpty()) return
        val cur = currentMic()
        val next = list[(list.indexOfFirst { it.id == cur?.id } + 1) % list.size]
        selectedMicId = next.id
        btnMic.text = micLabel(next)
    }

    // ------------------------------------------------------------ recording

    private fun startRecording() {
        if (!cameraOpen || camW == 0) {
            Toast.makeText(this, "Webcam belum siap", Toast.LENGTH_SHORT).show()
            return
        }
        val r = VideoRecorder(this, camW, camH, rotation == 90, currentMic())
        try {
            r.start()
        } catch (e: Exception) {
            Thread { r.stop() }.start()
            Toast.makeText(this, "Gagal mulai rekam: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        recorder = r
        recStartMs = System.currentTimeMillis()
        btnRec.text = "■ STOP"
        ui.post(timer)
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        btnRec.text = "● REKAM"
        status.text = "Menyimpan…"
        Thread {
            val res = r.stop()
            runOnUiThread {
                status.text = if (res != null) "Tersimpan di $res" else "Rekaman kosong, tidak disimpan"
            }
        }.start()
    }
}
