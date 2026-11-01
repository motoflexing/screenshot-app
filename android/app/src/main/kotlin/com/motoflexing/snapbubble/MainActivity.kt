package com.motoflexing.snapbubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val TAG = "ShotSvc"
    private val CHANNEL = "com.motoflexing.snapbubble/capture"
    private var pendingResult: MethodChannel.Result? = null
    private var projectionManager: android.media.projection.MediaProjectionManager? = null
    private val REQUEST_CODE = 1001
    private val OVERLAY_REQUEST_CODE = 1002

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            pendingResult = result
            when (call.method) {
                "start" -> requestProjectionPermission()
                "stop" -> stopCaptureService()
                "captureNow" -> captureNow()
                "hasOverlayPermission" -> checkOverlayPermission()
                "requestOverlayPermission" -> requestOverlayPermission()
                else -> result.notImplemented()
            }
        }
    }

    private fun requestProjectionPermission() {
        Log.d(TAG, "requestProjectionPermission")
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = projectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            startActivityForResult(intent, REQUEST_CODE)
        } else {
            Log.e(TAG, "Failed to create screen capture intent")
            pendingResult?.error("ERROR", "Failed to create screen capture intent", null)
            pendingResult = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult requestCode=$requestCode resultCode=$resultCode")
        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startCaptureService(resultCode, data)
            } else {
                Log.e(TAG, "User denied screen capture permission")
                pendingResult?.error("PERMISSION_DENIED", "User denied screen capture permission", null)
                pendingResult = null
            }
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCaptureService")
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        pendingResult?.success("STARTED")
        pendingResult = null
    }

    private fun stopCaptureService() {
        Log.d(TAG, "stopCaptureService")
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        stopService(serviceIntent)
        pendingResult?.success("STOPPED")
        pendingResult = null
    }

    private fun captureNow() {
        Log.d(TAG, "captureNow")
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_CAPTURE_NOW
        }
        startService(serviceIntent)
        // Result will be delivered via MethodChannel from CaptureService
        // pendingResult is kept to receive the callback
    }

    private fun checkOverlayPermission() {
        Log.d(TAG, "checkOverlayPermission")
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        pendingResult?.success(hasPermission)
        pendingResult = null
    }

    private fun requestOverlayPermission() {
        Log.d(TAG, "requestOverlayPermission")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_REQUEST_CODE)
            // Result will be checked when app resumes
            pendingResult?.success("REQUESTED")
            pendingResult = null
        } else {
            pendingResult?.success(true)
            pendingResult = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission not granted, but we don't request here - app handles it
            }
        }
    }
}