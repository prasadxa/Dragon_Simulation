package com.dragonsim.ar.ar

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import io.github.sceneview.utils.SurfaceMirrorer
import java.io.File

private const val TAG = "ArCapture"
private const val ALBUM = "DragonAR"

/**
 * Photo + video capture of the rendered AR view (camera feed + creature, no UI)
 * by mirroring SceneView's swap chain into an [ImageReader] / [MediaRecorder].
 * Saved to Pictures/DragonAR and Movies/DragonAR.
 */
class ArCapture(private val context: Context) {
    val mirrorer = SurfaceMirrorer()
    private val main = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var recording: Saved? = null

    val isRecording get() = recorder != null

    /** Grabs the next rendered frame. [onDone] gets the saved Uri or null on failure. */
    fun takePhoto(viewW: Int, viewH: Int, onDone: (Uri?) -> Unit) {
        val (w, h) = captureSize(viewW, viewH) ?: return onDone(null)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            mirrorer.stopMirroring(reader.surface)
            val uri = runCatching {
                val plane = image.planes[0]
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, w, h)
                savePhoto(bitmap)
            }.onFailure { Log.e(TAG, "photo failed", it) }.getOrNull()
            image.close()
            reader.close()
            onDone(uri)
        }, main)
        mirrorer.startMirroring(reader.surface, width = w, height = h)
    }

    fun startRecording(viewW: Int, viewH: Int): Boolean {
        if (recorder != null) return true
        val (w, h) = captureSize(viewW, viewH) ?: return false
        return runCatching {
            val out = newVideoTarget()
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(out.fd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(10_000_000)
            r.prepare()
            val surface = r.surface
            mirrorer.startMirroring(surface, width = w, height = h)
            r.start()
            recorder = r
            recorderSurface = surface
            recording = out
            true
        }.onFailure { Log.e(TAG, "recording failed to start", it) }.getOrDefault(false)
    }

    /** Stops and finalises the video; returns its Uri (null on failure). */
    fun stopRecording(): Uri? {
        val r = recorder ?: return null
        recorderSurface?.let { mirrorer.stopMirroring(it) }
        val ok = runCatching { r.stop() }.onFailure { Log.e(TAG, "stop failed", it) }.isSuccess
        r.release()
        recorder = null
        recorderSurface = null
        val out = recording ?: return null
        recording = null
        out.fd.close()
        // A failed stop (e.g. stopped before the first frame) leaves an unplayable
        // file — drop it rather than publishing a broken gallery entry.
        if (!ok) {
            discard(out.uri)
            return null
        }
        publish(out.uri)
        return out.uri
    }

    fun share(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private class Saved(val uri: Uri, val fd: ParcelFileDescriptor)

    /** View size capped at 1080 px on the short side, even dimensions (encoders need it). */
    private fun captureSize(viewW: Int, viewH: Int): Pair<Int, Int>? {
        if (viewW <= 0 || viewH <= 0) return null
        val k = minOf(1f, 1080f / minOf(viewW, viewH))
        return ((viewW * k).toInt() and 1.inv()) to ((viewH * k).toInt() and 1.inv())
    }

    private fun savePhoto(bitmap: Bitmap): Uri? {
        val name = "dragon_${System.currentTimeMillis()}.jpg"
        val uri = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/jpeg", Environment.DIRECTORY_PICTURES)
            ?: return null
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.getOrNull() == true
        if (!written) {
            discard(uri)
            return null
        }
        publish(uri)
        return uri
    }

    private fun newVideoTarget(): Saved {
        val name = "dragon_${System.currentTimeMillis()}.mp4"
        val uri = insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, name, "video/mp4", Environment.DIRECTORY_MOVIES)
            ?: error("could not create video entry")
        val fd = context.contentResolver.openFileDescriptor(uri, "w") ?: error("no fd for $uri")
        return Saved(uri, fd)
    }

    private fun insert(collection: Uri, name: String, mime: String, dir: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) {
            // Pre-Q: app-specific storage needs no permission.
            val folder = File(context.getExternalFilesDir(dir), ALBUM).apply { mkdirs() }
            return Uri.fromFile(File(folder, name))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
    }

    private fun discard(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            else context.contentResolver.delete(uri, null, null)
        }.onFailure { Log.w(TAG, "could not discard $uri", it) }
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT < 29 || uri.scheme == "file") return
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }
}
