package com.khovre.moonpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Floating side bubble. Tap = open menu, drag = move (snaps to the nearest screen edge). */
class BubbleService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var menu: LinearLayout? = null
    private lateinit var blp: WindowManager.LayoutParams
    private var sw = 0; private var sh = 0; private var size = 0

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("pet", "Moon pet", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "pet").setContentTitle("Moon pet bubble is on").setSmallIcon(android.R.drawable.star_on).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        Pet.load(applicationContext)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = wm.currentWindowMetrics.bounds
        sw = b.width(); sh = b.height(); size = dp(52)

        val bv = TextView(this).apply {
            text = "👾"; textSize = 24f; gravity = Gravity.CENTER; alpha = .8f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(150, 20, 28, 48)); setStroke(dp(2), Color.argb(120, 160, 240, 200)) }
        }
        blp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = sw - size; y = (sh * .35f).toInt()
        }
        var sx = 0; var sy = 0; var dx = 0f; var dy = 0f; var moved = false
        bv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = blp.x; sy = blp.y; dx = e.rawX; dy = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx; val my = e.rawY - dy
                    if (abs(mx) > 12 || abs(my) > 12) moved = true
                    if (moved) { closeMenu(); blp.x = (sx + mx).toInt(); blp.y = (sy + my).toInt(); wm.updateViewLayout(bv, blp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                    else { blp.x = if (blp.x + size / 2 < sw / 2) 0 else sw - size; blp.y = blp.y.coerceIn(0, sh - size); wm.updateViewLayout(bv, blp) }
                    true
                }
                else -> false
            }
        }
        wm.addView(bv, blp); bubble = bv
    }

    private fun toggleMenu() { if (menu != null) closeMenu() else openMenu() }

    private fun openMenu() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun item(label: String, action: () -> Unit) {
            val tv = TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 16f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.argb(210, 20, 28, 48)) }
                setOnClickListener { action(); closeMenu() }
            }
            col.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        item("🍇  Feed") { Pet.feed() }
        item("🤚  Pet") { Pet.pet() }
        item("🎾  Play") { Pet.play() }
        item("🫧  Groom") { Pet.groom() }
        item(if (Pet.sleeping) "☀️  Wake" else "🌙  Sleep") { Pet.toggleSleep() }
        item("📊  Status") { Toast.makeText(this, Pet.status(), Toast.LENGTH_LONG).show() }
        col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val mw = col.measuredWidth; val mh = col.measuredHeight
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (blp.x + size / 2 > sw / 2) blp.x - mw - dp(8) else blp.x + size + dp(8)
            y = blp.y.coerceIn(0, maxOf(0, sh - mh - dp(24)))
        }
        wm.addView(col, lp); menu = col
    }

    private fun closeMenu() { menu?.let { try { wm.removeView(it) } catch (e: Exception) {} }; menu = null }

    override fun onDestroy() {
        closeMenu()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        bubble = null
        Pet.save(applicationContext)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
