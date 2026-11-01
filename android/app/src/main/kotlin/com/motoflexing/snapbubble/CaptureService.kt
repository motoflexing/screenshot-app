package com.motoflexing.snapbubble

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.nio.ByteBuffer

class CaptureService : Service() {

    companion object {
        private const val TAG = "ShotSvc"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.motoflexing.snapbubble.START_CAPTURE"
        const val ACTION_STOP = "com.motoflexing.snapbubble.STOP_CAPTURE"
        const val ACTION_CAPTURE_NOW = "com.motoflexing.snapbubble.CAPTURE_NOW"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL_NAME = "com.motoflexing.snapbubble/capture"

        // MethodChannel callback for sending results to Flutter
        @Suppress("UNUSED_PARAMETER")
        var methodChannelCallback: ((String) -> Unit)? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmap: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())

    // Bubble overlay
    private var bubbleView: View? = null
    private var windowManager: WindowManager? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    // Capture state
    private var isCapturing = false
    private var pendingCaptureResult: ((String) -> Unit)? = null
    private var frameWaitRunnable: Runnable? = null
    private var currentFrameListener: ImageReader.OnImageAvailableListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for screen capture"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Tap to return to app")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createBubbleView(): View {
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        val bubble = View(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(sizePx, sizePx)
            setBackgroundResource(android.R.drawable.ic_menu_camera)
            // Create a circular background
            val paint = Paint().apply {
                color = Color.BLUE
                isAntiAlias = true
            }
            val drawable = ShapeDrawable(OvalShape()).apply {
                paint.color = Color.BLUE
            }
            setBackground(drawable)
        }

        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.7f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1.0f
                    if (event.action == MotionEvent.ACTION_UP) {
                        onBubbleTap()
                    }
                    true
                }
                else -> false
            }
        }

        return bubble
    }

    private fun addBubble() {
        if (bubbleView != null) return

        Log.d(TAG, "Adding bubble overlay")
        bubbleView = createBubbleView()

        val sizePx = (56 * resources.displayMetrics.density).toInt()
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)

        bubbleLayoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Right edge, vertically centered
            x = displayMetrics.widthPixels - sizePx
            y = (displayMetrics.heightPixels - sizePx) / 2
        }

        try {
            windowManager?.addView(bubbleView!!, bubbleLayoutParams!!)
            Log.d(TAG, "Bubble added at x=${bubbleLayoutParams?.x}, y=${bubbleLayoutParams?.y}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble", e)
            bubbleView = null
        }
    }

    private fun removeBubble() {
        if (bubbleView != null) {
            Log.d(TAG, "Removing bubble")
            try {
                windowManager?.removeView(bubbleView!!)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove bubble", e)
            }
            bubbleView = null
            bubbleLayoutParams = null
        }
    }

    private fun onBubbleTap() {
        Log.d(TAG, "Bubble tapped")
        if (isCapturing) {
            Log.d(TAG, "Capture in progress, ignoring tap")
            return
        }

        if (latestBitmap == null) {
            Log.e(TAG, "No frame available to capture")
            return
        }

        isCapturing = true
        performHapticFeedback()

        // Hide bubble
        bubbleView?.visibility = View.INVISIBLE
        Log.d(TAG, "Bubble hidden")

        // Wait for a NEW frame after hiding, with 500ms timeout
        waitForNewFrameAfterHide { frameBitmap ->
            captureAndSaveFrame(frameBitmap)
        }
    }

    private fun waitForNewFrameAfterHide(onFrame: (Bitmap) -> Unit) {
        var frameReceived = false

        frameWaitRunnable = Runnable {
            if (!frameReceived) {
                Log.d(TAG, "Frame wait timeout, using latest frame")
                latestBitmap?.let { onFrame(it) }
            }
        }
        handler.postDelayed(frameWaitRunnable!!, 500)

        // Temporarily replace the frame listener to catch the next frame
        // Store current listener and replace it
        val originalListener = currentFrameListener
        val newListener = ImageReader.OnImageAvailableListener { reader ->
            if (!frameReceived) {
                frameReceived = true
                handler.removeCallbacks(frameWaitRunnable!!)
                frameWaitRunnable = null

                val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride

                    val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(image.width * image.height)

                    buffer.rewind()

                    if (pixelStride == 4 && rowStride == image.width * 4) {
                        buffer.asIntBuffer().get(pixels)
                    } else {
                        for (y in 0 until image.height) {
                            val rowOffset = y * rowStride
                            for (x in 0 until image.width) {
                                val pixelOffset = rowOffset + x * pixelStride
                                if (pixelOffset + 3 < buffer.capacity()) {
                                    buffer.position(pixelOffset)
                                    val r = (buffer.get().toInt() and 0xFF)
                                    val g = (buffer.get().toInt() and 0xFF)
                                    val b = (buffer.get().toInt() and 0xFF)
                                    val a = (buffer.get().toInt() and 0xFF)
                                    pixels[y * image.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                        }
                    }

                    bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                    Log.d(TAG, "New frame received after bubble hide")
                    onFrame(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring new frame", e)
                    latestBitmap?.let { onFrame(it) }
                } finally {
                    image.close()
                }
            }
            // Restore original listener
            imageReader?.setOnImageAvailableListener(originalListener, handler)
            currentFrameListener = originalListener
        }
        currentFrameListener = newListener
        imageReader?.setOnImageAvailableListener(newListener, handler)
    }

    private fun captureAndSaveFrame(bitmap: Bitmap) {
        handler.post {
            saveToMediaStore(bitmap) { result ->
                // Show bubble again
                bubbleView?.visibility = View.VISIBLE
                Log.d(TAG, "Bubble shown")

                isCapturing = false

                // Send result to Flutter
                CaptureService.methodChannelCallback?.invoke(result)
                CaptureService.methodChannelCallback = null
            }
        }
    }

    private fun performHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                val vibrator = vibratorManager.getDefaultVibrator()
                vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(10)
                }
            }
            Log.d(TAG, "Haptic feedback triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger haptic", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra<Intent>(EXTRA_DATA)
            }

            // Always call startForeground first (required on Android 14+ for mediaProjection)
            startForeground(NOTIFICATION_ID, createForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            Log.d(TAG, "startForeground called")

            if (resultCode == Activity.RESULT_OK && data != null) {
                initMediaProjection(resultCode, data)
            } else {
                Log.e(TAG, "Invalid resultCode ($resultCode) or data in start intent")
                stopSelf()
            }
        } else if (intent?.action == ACTION_STOP) {
            // For STOP action, still need to startForeground to satisfy foreground service requirements
            startForeground(NOTIFICATION_ID, createForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            stopCapture()
            stopSelf()
        } else if (intent?.action == ACTION_CAPTURE_NOW) {
            // For CAPTURE_NOW, service should already be running with foreground
            captureNow()
        }

        return START_NOT_STICKY
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        Log.d(TAG, "initMediaProjection")
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                handler.post { stopCapture() }
            }
        }, handler)
        Log.d(TAG, "MediaProjection callback registered")

        createVirtualDisplay()
    }

    private fun createVirtualDisplay() {
        Log.d(TAG, "createVirtualDisplay")
        val metrics = DisplayMetrics()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        Log.d(TAG, "Display metrics: ${width}x${height} @ ${density}dpi")

        imageReader = ImageReader.newInstance(width, height, 0x1, 2) // 0x1 = RGBA_8888
            .also { reader ->
                currentFrameListener = ImageReader.OnImageAvailableListener { reader ->
                    acquireLatestFrame(reader)
                }
                reader.setOnImageAvailableListener(currentFrameListener!!, handler)
            }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CaptureDisplay",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
        Log.d(TAG, "VirtualDisplay created: ${virtualDisplay != null}")

        // Add bubble after VirtualDisplay is ready
        addBubble()
    }

    private fun acquireLatestFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            Log.d(TAG, "Frame acquired: ${image.width}x${image.height}, rowStride=$rowStride, pixelStride=$pixelStride")

            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(image.width * image.height)

            buffer.rewind()

            if (pixelStride == 4 && rowStride == image.width * 4) {
                // Fast path: contiguous rows
                buffer.asIntBuffer().get(pixels)
            } else {
                // Slow path: handle row stride and pixel stride
                for (y in 0 until image.height) {
                    val rowOffset = y * rowStride
                    for (x in 0 until image.width) {
                        val pixelOffset = rowOffset + x * pixelStride
                        if (pixelOffset + 3 < buffer.capacity()) {
                            buffer.position(pixelOffset)
                            val r = (buffer.get().toInt() and 0xFF)
                            val g = (buffer.get().toInt() and 0xFF)
                            val b = (buffer.get().toInt() and 0xFF)
                            val a = (buffer.get().toInt() and 0xFF)
                            pixels[y * image.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }

            bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)

            // Release previous bitmap
            latestBitmap?.recycle()
            latestBitmap = bitmap
            Log.d(TAG, "Latest frame updated")

        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring frame", e)
        } finally {
            image.close()
        }
    }

    private fun captureNow() {
        Log.d(TAG, "captureNow called (from Flutter)")
        if (latestBitmap == null) {
            Log.e(TAG, "No frame available to capture")
            CaptureService.methodChannelCallback?.invoke("ERROR: No frame available")
            CaptureService.methodChannelCallback = null
            return
        }

        isCapturing = true
        performHapticFeedback()

        // Hide bubble if it exists
        bubbleView?.visibility = View.INVISIBLE
        Log.d(TAG, "Bubble hidden for captureNow")

        waitForNewFrameAfterHide { frameBitmap ->
            captureAndSaveFrame(frameBitmap)
        }
    }

    private fun saveToMediaStore(bitmap: Bitmap, onComplete: (String) -> Unit) {
        Log.d(TAG, "Saving to MediaStore")
        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "snapbubble_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri: Uri? = try {
            resolver.insert(collection, contentValues)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert MediaStore entry", e)
            onComplete("ERROR: Failed to insert MediaStore entry: ${e.message}")
            return
        }

        uri?.let { imageUri ->
            try {
                val outputStream: OutputStream = resolver.openOutputStream(imageUri)!!
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.flush()
                outputStream.close()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updateValues = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(imageUri, updateValues, null, null)
                }
                Log.d(TAG, "Saved to gallery: $imageUri")
                onComplete("SAVED: $imageUri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write bitmap to MediaStore", e)
                try {
                    resolver.delete(imageUri, null, null)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to cleanup failed insert", e2)
                }
                onComplete("ERROR: Failed to save: ${e.message}")
            }
        } ?: run {
            onComplete("ERROR: Failed to create MediaStore entry")
        }
    }

    private fun stopCapture() {
        Log.d(TAG, "stopCapture")

        // Remove bubble
        removeBubble()

        // Cancel any pending frame wait
        frameWaitRunnable?.let { handler.removeCallbacks(it) }
        frameWaitRunnable = null

        virtualDisplay?.release()
        virtualDisplay = null
        Log.d(TAG, "VirtualDisplay released")

        imageReader?.close()
        imageReader = null
        Log.d(TAG, "ImageReader closed")

        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "MediaProjection stopped")

        latestBitmap?.recycle()
        latestBitmap = null
        Log.d(TAG, "Bitmap recycled")

        stopForeground(true)
        Log.d(TAG, "Foreground stopped")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}