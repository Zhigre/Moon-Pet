package com.khovre.moonpet

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 120, 48, 48); setBackgroundColor(Color.BLACK) }
        l.addView(TextView(this).apply { text = "👾 Moon Pet setup"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, 48) })
        fun btn(t: String, a: () -> Unit) { l.addView(Button(this).apply { text = t; setOnClickListener { a() } }) }
        btn("1. Allow floating bubble (Display over other apps)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. Allow notifications (keeps bubble alive)") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        btn("3. Set as live wallpaper") {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, MoonWallpaperService::class.java)))
        }
        btn("4. Start floating bubble") {
            if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, BubbleService::class.java))
            else Toast.makeText(this, "Do step 1 first", Toast.LENGTH_LONG).show()
        }
        btn("Stop bubble") { stopService(Intent(this, BubbleService::class.java)) }
        setContentView(l)
    }
}
