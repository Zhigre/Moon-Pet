package com.khovre.moonpet

import android.app.WallpaperManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class MoonWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MoonEngine()

    inner class MoonEngine : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var last = 0L
        private var powerAt = 0L
        private val sm by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
        private val frame = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, if (Pet.sleeping) 50L else 33L)
            }
        }

        override fun onCreate(sh: SurfaceHolder) {
            super.onCreate(sh)
            Pet.load(applicationContext)
        }

        override fun onSurfaceChanged(sh: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(sh, format, w, h)
            Pet.resize(w, h)
            drawFrame()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            Pet.visible = v
            if (v) {
                Pet.onShow(); readPower()
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                last = System.nanoTime()
                handler.removeCallbacks(frame); handler.post(frame)
            } else {
                handler.removeCallbacks(frame)
                sm.unregisterListener(this)
                Pet.onHide(); Pet.save(applicationContext)
            }
        }

        override fun onDestroy() {
            visible = false; Pet.visible = false
            handler.removeCallbacks(frame); sm.unregisterListener(this)
            Pet.save(applicationContext)
            super.onDestroy()
        }

        override fun onOffsetsChanged(xOff: Float, yOff: Float, xStep: Float, yStep: Float, xPix: Int, yPix: Int) { Pet.offX = xOff - .5f }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) Pet.tap(x.toFloat(), y.toFloat())
            return null
        }

        override fun onSensorChanged(e: SensorEvent) { if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) Pet.onAccel(e.values[0], e.values[1], e.values[2]) }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        private fun readPower() {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            Pet.setPower(lvl * 100 / sc, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }

        private fun drawFrame() {
            val sh = surfaceHolder
            var c: Canvas? = null
            try {
                c = try { sh.lockHardwareCanvas() } catch (e: Exception) { sh.lockCanvas() }
                if (c != null) {
                    val now = System.nanoTime()
                    if (now - powerAt > 5_000_000_000L) { powerAt = now; readPower() }
                    Pet.update((now - last) / 1e9f); last = now
                    Pet.draw(c)
                }
            } finally {
                if (c != null) try { sh.unlockCanvasAndPost(c) } catch (e: Exception) {}
            }
        }
    }
}
