package com.example.webcamvertical

import android.content.ContentValues
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Merekam frame NV21 dari webcam UVC (diputar jadi vertikal) + audio dari mic (USB bila ada)
 * menjadi file MP4 (H.264 + AAC).
 */
class VideoRecorder(
    private val ctx: Context,
    private val camW: Int,
    private val camH: Int,
    private val rotate90Clockwise: Boolean,
    private val mic: AudioDeviceInfo?
) {
    class Frame(val data: ByteArray) { var ts = 0L }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FPS = 30
    }

    // Ukuran output = ukuran kamera diputar 90 derajat (misal 1280x720 -> 720x1280)
    private val outW = camH
    private val outH = camW
    private val frameSize = camW * camH * 3 / 2

    private val pool = ArrayBlockingQueue<Frame>(4)
    private val queue = ArrayBlockingQueue<Frame>(3)

    private val yRow = ByteArray(outW)
    private val uRow = ByteArray(outW / 2)
    private val vRow = ByteArray(outW / 2)

    @Volatile private var running = false
    @Volatile var micName: String = "-"
        private set

    private var startNs = 0L
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null

    // ---------- Muxer ----------
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var outFile: File? = null
    private val lock = Any()
    private var vTrack = -1
    private var aTrack = -1
    private var muxStarted = false
    private val lastPts = HashMap<Int, Long>()
    private class Pending(val track: Int, val data: ByteBuffer, val info: BufferInfo)
    private val pending = ArrayList<Pending>()

    init {
        repeat(4) { pool.offer(Frame(ByteArray(frameSize))) }
    }

    /** Dipanggil dari callback frame kamera (NV21). Frame dibuang bila encoder sibuk. */
    fun onFrame(src: ByteBuffer) {
        if (!running) return
        val f = pool.poll() ?: return
        val d = src.duplicate()
        d.clear()
        if (d.remaining() < frameSize) { pool.offer(f); return }
        d.get(f.data, 0, frameSize)
        f.ts = System.nanoTime()
        if (!queue.offer(f)) pool.offer(f)
    }

    fun start() {
        openOutput()
        startNs = System.nanoTime()

        // ----- Video encoder -----
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        vEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }

        // ----- Audio encoder -----
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }

        // ----- Audio source (mic USB bila dipilih/ada) -----
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 16384)
        )
        check(ar.state == AudioRecord.STATE_INITIALIZED) { "Mic tidak bisa dibuka" }
        if (mic != null) ar.setPreferredDevice(mic)
        audioRecord = ar

        running = true
        ar.startRecording()
        micName = ar.routedDevice?.productName?.toString() ?: (mic?.productName?.toString() ?: "default")

        vThread = Thread({ videoLoop() }, "video-enc").also { it.start() }
        aThread = Thread({ audioLoop() }, "audio-enc").also { it.start() }
    }

    /** Menghentikan rekaman dan menutup file. Mengembalikan lokasi file (atau null jika kosong). */
    fun stop(): String? {
        running = false
        vThread?.join(4000)
        aThread?.join(4000)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { vEnc?.stop() } catch (_: Exception) {}
        try { vEnc?.release() } catch (_: Exception) {}
        try { aEnc?.stop() } catch (_: Exception) {}
        try { aEnc?.release() } catch (_: Exception) {}
        return finishOutput()
    }

    // ---------------------------------------------------------------- loops

    private fun videoLoop() {
        val enc = vEnc ?: return
        try {
            while (running) {
                val f = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val idx = enc.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    val img = enc.getInputImage(idx)
                    if (img != null) {
                        fill(img, f.data)
                        val pts = maxOf(0L, (f.ts - startNs) / 1000)
                        enc.queueInputBuffer(idx, 0, outW * outH * 3 / 2, pts, 0)
                    } else {
                        enc.queueInputBuffer(idx, 0, 0, 0, 0)
                    }
                }
                pool.offer(f)
                drain(enc, true, false)
            }
            sendEos(enc)
            drain(enc, true, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun audioLoop() {
        val enc = aEnc ?: return
        val ar = audioRecord ?: return
        val buf = ByteArray(4096)
        var totalSamples = 0L
        var baseUs = -1L
        try {
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) Thread.sleep(10); continue }
                val nowUs = (System.nanoTime() - startNs) / 1000
                val samples = n / 2
                if (baseUs < 0) baseUs = maxOf(0L, nowUs - samples * 1_000_000L / SAMPLE_RATE)
                val pts = baseUs + totalSamples * 1_000_000L / SAMPLE_RATE
                totalSamples += samples
                feedAudio(enc, buf, n, pts)
                drain(enc, false, false)
            }
            sendEos(enc)
            drain(enc, false, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun feedAudio(enc: MediaCodec, data: ByteArray, n: Int, pts: Long) {
        repeat(50) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = enc.getInputBuffer(idx)!!
                ib.clear()
                ib.put(data, 0, n)
                enc.queueInputBuffer(idx, 0, n, pts, 0)
                return
            }
            drain(enc, false, false)
        }
    }

    private fun sendEos(enc: MediaCodec) {
        repeat(50) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                enc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    private fun drain(enc: MediaCodec, isVideo: Boolean, untilEos: Boolean) {
        val info = BufferInfo()
        var idleTries = 0
        while (true) {
            val i = enc.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    if (++idleTries > 300) return
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(isVideo, enc.outputFormat)
                i >= 0 -> {
                    val out = enc.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    val track = if (isVideo) vTrack else aTrack
                    if (out != null && info.size > 0 && track >= 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        writeSample(track, out, info)
                    }
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ---------------------------------------------------------------- frame conversion

    /** Putar NV21 (camW x camH) 90 derajat dan tulis langsung ke Image encoder. */
    private fun fill(img: Image, src: ByteArray) {
        // --- Y ---
        val yp = img.planes[0]
        val yBuf = yp.buffer
        val yStride = yp.rowStride
        for (yd in 0 until outH) {
            var si: Int
            val step: Int
            if (rotate90Clockwise) { si = (camH - 1) * camW + yd; step = -camW }
            else { si = camW - 1 - yd; step = camW }
            for (xd in 0 until outW) { yRow[xd] = src[si]; si += step }
            yBuf.position(yd * yStride)
            yBuf.put(yRow, 0, outW)
        }
        // --- UV (NV21: V lalu U berselang-seling) ---
        val ys = camW * camH
        val cw = camW / 2
        val ch = camH / 2
        val cOutW = outW / 2
        val cOutH = outH / 2
        val uP = img.planes[1]
        val vP = img.planes[2]
        for (yd in 0 until cOutH) {
            var si: Int
            val step: Int
            if (rotate90Clockwise) { si = ys + (ch - 1) * camW + 2 * yd; step = -camW }
            else { si = ys + (cw - 1 - yd) * 2; step = camW }
            for (xd in 0 until cOutW) {
                vRow[xd] = src[si]
                uRow[xd] = src[si + 1]
                si += step
            }
            writeChroma(uP, yd, uRow, cOutW)
            writeChroma(vP, yd, vRow, cOutW)
        }
    }

    private fun writeChroma(p: Image.Plane, row: Int, data: ByteArray, n: Int) {
        val b = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val base = row * rs
        if (ps == 1) {
            b.position(base)
            b.put(data, 0, n)
        } else {
            for (i in 0 until n) b.put(base + i * ps, data[i])
        }
    }

    // ---------------------------------------------------------------- output / muxer

    private fun openOutput() {
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WebcamVertical")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Tidak bisa membuat file")
            outUri = uri
            pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
            muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "WebcamVertical"
            )
            dir.mkdirs()
            outFile = File(dir, name)
            muxer = MediaMuxer(outFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun addTrack(isVideo: Boolean, fmt: MediaFormat) {
        synchronized(lock) {
            val idx = muxer!!.addTrack(fmt)
            if (isVideo) vTrack = idx else aTrack = idx
            if (vTrack >= 0 && aTrack >= 0 && !muxStarted) {
                muxer!!.start()
                muxStarted = true
                for (p in pending) rawWrite(p.track, p.data, p.info)
                pending.clear()
            }
        }
    }

    private fun writeSample(track: Int, data: ByteBuffer, info: BufferInfo) {
        synchronized(lock) {
            if (muxStarted) {
                rawWrite(track, data, info)
            } else {
                val copy = ByteBuffer.allocate(info.size)
                copy.put(data)
                copy.flip()
                val bi = BufferInfo()
                bi.set(0, info.size, info.presentationTimeUs, info.flags)
                pending.add(Pending(track, copy, bi))
            }
        }
    }

    private fun rawWrite(track: Int, data: ByteBuffer, info: BufferInfo) {
        val last = lastPts[track] ?: -1L
        if (info.presentationTimeUs <= last) info.presentationTimeUs = last + 1
        lastPts[track] = info.presentationTimeUs
        muxer!!.writeSampleData(track, data, info)
    }

    private fun finishOutput(): String? {
        var result: String? = null
        synchronized(lock) {
            val started = muxStarted
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            muxer = null
            val uri = outUri
            val file = outFile
            if (uri != null) {
                if (started) {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    ctx.contentResolver.update(uri, v, null, null)
                    result = "Movies/WebcamVertical"
                } else {
                    ctx.contentResolver.delete(uri, null, null)
                }
            } else if (file != null) {
                if (started) {
                    MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
                    result = file.absolutePath
                } else {
                    file.delete()
                }
            }
        }
        return result
    }
}
