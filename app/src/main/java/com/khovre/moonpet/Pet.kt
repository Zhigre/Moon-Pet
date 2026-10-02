package com.khovre.moonpet

import android.content.Context
import android.graphics.*
import java.util.Calendar
import kotlin.math.*
import kotlin.random.Random

/** Pet brain + renderer. Everything runs on the main thread (wallpaper engine + bubble service). */
object Pet {
    private val rnd = Random(System.nanoTime())

    // ---- persistent stats ----
    var hunger = 80f; var joy = 80f; var energy = 80f; var clean = 80f
    var sleeping = false
    var name = "Blip"
    var born = System.currentTimeMillis()

    // ---- live inputs ----
    var visible = false
    var onVis: ((Boolean) -> Unit)? = null
    var shake = 0f; var tilt = 0f; var offX = 0f
    var battery = 100; var charging = false

    var W = 1080f; var H = 2400f
    private val sc: Float get() = W / 400f
    private val hz: Float get() = H * .62f
    private fun yAt(d: Float) = H * (.668f + .172f * d)   // depth 0 = far (horizon), 1 = near (above dock)
    private fun scAt(d: Float) = .1f + .9f * d
    private val gy: Float get() = yAt(pd)
    private val rr: Float get() = W * .11f * scAt(pd)
    private var bmp: Bitmap? = null

    private var loaded = false
    private var lastTick = System.currentTimeMillis()
    private var hiddenAt = 0L
    private var prevM = 0f; private var gx = 0f
    private var t = 0f
    private var px = 0f; private var pz = 0f; private var pvz = 0f
    private var beh = 0; private var bt = 0f; private var spdM = 1f
    private var misses = 0
    private var zt = 0f; private var moving = false; private var critT = 6f; private var ufoT = 50f
    var gems = 0
    var menuOpen = false; var menuRight = true
    private var sleepBlend = 0f; private var nightA = 0f
    private var lastCare = System.currentTimeMillis()
    private var pd = .6f; private var td = -1f; private var fx = 0f; private var fb = 1f
    private var tx = -1f; private var act = 2f; private var blink = 3f
    private var look = 0f; private var up = 0f; private var dizzy = 0f; private var eat = 0f; private var stepD = 0f
    private var glow = 0f; private var hourT = 0f; private var lastHour = -1; private var night = false
    private var nextMeteor = 10f; private var zapT = 0f

    class Food(var x: Float, var y: Float, val d: Float) { var vy = 0f; var landed = false }
    class Ball(var x: Float, var z: Float, var vx: Float, var vz: Float, var life: Float, val d: Float)
    class Part(var x: Float, var y: Float, var vx: Float, var vy: Float, val g: Float, var life: Float, val max: Float, val ch: String?, val s: Float)
    class Met(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float) { var seen = false }
    class Print(val x: Float, val y: Float, var a: Float)
    class Crit(var x: Float, val d: Float, val gold: Boolean) { var st = 0; var t = 0f; var dir = 0; var hx = 0f; var spd = 300f + rnd.nextFloat() * 120f; var hole: Hole? = null; var digT = 2f }
    class Hole(val x: Float, val d: Float) { var age = 0f; var life = 10f; var grow = 1f }
    class Ufo(var x: Float, val y: Float, val vx: Float, val will: Boolean) { var gift = false }
    private val foods = ArrayList<Food>()
    private var ball: Ball? = null
    private val parts = ArrayList<Part>()
    private val mets = ArrayList<Met>()
    private val prints = ArrayList<Print>()
    private val crits = ArrayList<Crit>()
    private val holes = ArrayList<Hole>()
    private var ufo: Ufo? = null

    // ---- persistence ----
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val s = c.getSharedPreferences("pet", 0)
        hunger = s.getFloat("hu", 80f); joy = s.getFloat("jo", 80f)
        energy = s.getFloat("en", 80f); clean = s.getFloat("cl", 80f)
        born = s.getLong("born", System.currentTimeMillis())
        lastTick = s.getLong("t", System.currentTimeMillis())
        sleeping = s.getBoolean("sl", false); gems = s.getInt("gems", 0)
        name = s.getString("name", "Blip") ?: "Blip"
        bmp = try { c.assets.open("moon.jpg").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        catchUp()
    }

    fun save(c: Context) {
        c.getSharedPreferences("pet", 0).edit()
            .putFloat("hu", hunger).putFloat("jo", joy).putFloat("en", energy).putFloat("cl", clean)
            .putLong("born", born).putLong("t", System.currentTimeMillis())
            .putBoolean("sl", sleeping).putInt("gems", gems).putString("name", name).apply()
    }

    /** Time passed while the wallpaper wasn't being drawn: age the pet gently. */
    fun catchUp() {
        val now = System.currentTimeMillis()
        val el = min((now - lastTick) / 1000f, 18000f) * .3f
        hunger = max(8f, hunger - el * .012f); joy = max(8f, joy - el * .015f); clean = max(10f, clean - el * .006f)
        energy = if (sleeping) min(100f, energy + el * .5f) else max(8f, energy - el * .008f)
        lastTick = now
    }

    fun onShow() {
        catchUp()
        val n = System.currentTimeMillis()
        if (hiddenAt != 0L && n - hiddenAt > 180000L) greet()
    }
    fun onHide() { hiddenAt = System.currentTimeMillis() }

    // ---- inputs ----
    fun onAccel(x: Float, y: Float, z: Float) {
        val m = sqrt(x * x + y * y + z * z)
        val d = abs(m - prevM); prevM = m
        if (d > 9f) shake = min(1f, shake + (d - 9f) / 25f)
        gx = gx * .9f + x * .1f
        val v = (-gx / 6f).coerceIn(-1f, 1f)
        tilt = if (abs(v) < .15f) 0f else v
    }
    fun setPower(pct: Int, chg: Boolean) { battery = pct; charging = chg }

    // ---- actions (called from the bubble menu) ----
    fun feed() {
        lastCare = System.currentTimeMillis()
        if (foods.size >= 3) foods.removeAt(0)
        sleeping = false
        if (visible) foods.add(Food((px + (rnd.nextFloat() - .5f) * W * .5f).coerceIn(30f * sc, W - 30f * sc), -20f * sc, .2f + rnd.nextFloat() * .8f))
        else { hunger = min(100f, hunger + 25f); joy = min(100f, joy + 3f) }
    }
    fun pet() {
        lastCare = System.currentTimeMillis(); beh = 0
        sleeping = false; joy = min(100f, joy + 6f)
        if (visible) { hearts(3); if (pz <= 0f) pvz = 230f * sc }
    }
    fun play() {
        lastCare = System.currentTimeMillis()
        sleeping = false
        if (visible) { val left = rnd.nextBoolean(); ball = Ball(if (left) 30f * sc else W - 30f * sc, 60f * sc, if (left) 120f * sc else -120f * sc, 200f * sc, 25f, .3f + rnd.nextFloat() * .7f) }
        else { joy = min(100f, joy + 12f); energy = max(0f, energy - 4f) }
    }
    fun groom() {
        lastCare = System.currentTimeMillis()
        sleeping = false; clean = 100f; joy = min(100f, joy + 4f)
        if (visible) {
            repeat(9) { emoji("🫧", px + (rnd.nextFloat() - .5f) * rr * 1.6f, gy - rr * (.3f + rnd.nextFloat() * 1.5f), (rnd.nextFloat() - .5f) * 30f * sc, -40f * sc, 1.4f, 18f) }
            if (pz <= 0f) pvz = 200f * sc
        }
    }
    fun toggleSleep() { sleeping = !sleeping; lastCare = System.currentTimeMillis() }
    fun greet() { if (sleeping) return; joy = min(100f, joy + 3f); if (pz <= 0f) pvz = 220f * sc; look = 0f; hearts(2) }
    fun tap(x: Float, y: Float) {
        if (abs(x - px) < rr * 1.1f && y > gy - pz - rr * 2.2f && y < gy + rr * .3f) pet()
        else if (y < hz) mets.add(mkMeteor())
    }
    fun status(): String {
        val days = 1 + (System.currentTimeMillis() - born) / 86400000L
        return "$name · day $days\nFood ${hunger.toInt()}%  Joy ${joy.toInt()}%\nEnergy ${energy.toInt()}%  Clean ${clean.toInt()}%\n💎 $gems"
    }

    private fun hearts(n: Int) = repeat(n) { emoji("❤️", px + (rnd.nextFloat() - .5f) * rr * 1.6f, gy - pz - rr * 2f, (rnd.nextFloat() - .5f) * 20f * sc, -50f * sc, 1.4f, 20f) }
    private fun emoji(ch: String, x: Float, y: Float, vx: Float, vy: Float, life: Float, s: Float, g: Float = 0f) { parts.add(Part(x, y, vx, vy, g, life, life, ch, s)) }
    private fun mkMeteor() = Met(rnd.nextFloat() * W * .8f + W * .2f, rnd.nextFloat() * hz * .4f, (-260f - rnd.nextFloat() * 160f) * sc, (90f + rnd.nextFloat() * 80f) * sc, 1.4f)

    // ---- simulation ----
    fun update(dt0: Float) {
        val dt = min(dt0, .05f); t += dt; lastTick = System.currentTimeMillis()
        val k = sc
        sleepBlend += ((if (sleeping || beh == 8) 1f else 0f) - sleepBlend) * min(1f, dt * 2.5f)
        nightA += ((if (sleeping) 1f else 0f) - nightA) * min(1f, dt * 2f)
        val mood = (hunger + joy + energy) / 3f
        hourT -= dt
        if (hourT <= 0f) {
            hourT = 5f
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            night = h >= 23 || h < 6
            if (h != lastHour) { if (h == 23) sleeping = true; if (h == 6) sleeping = false; lastHour = h }
        }
        // needs
        hunger = max(0f, hunger - dt * .03f * (if (sleeping) .3f else 1f))
        joy = max(0f, joy - dt * .03f * (if (sleeping) 0f else 1f) * (if (battery < 15 && !charging) 2f else 1f))
        clean = max(0f, clean - dt * .006f)
        if (charging) { energy = min(100f, energy + dt * 1.2f); glow = min(1f, glow + dt); zapT -= dt
            if (zapT <= 0f && !sleeping) { zapT = .6f; emoji("⚡", px + (rnd.nextFloat() - .5f) * rr * 1.8f, gy - rr * 1.8f, 0f, -40f * k, 1f, 16f) } }
        else glow = max(0f, glow - dt)
        if (sleeping) { energy = min(100f, energy + dt * .9f); if (energy >= 100f && !night) sleeping = false }
        else { energy = max(0f, energy - dt * .008f); if (energy < 6f) sleeping = true }
        // shake
        shake *= exp(-dt * 1.8f)
        if (shake > .45f) {
            dizzy = 2.5f; joy = max(0f, joy - dt * 10f); clean = max(0f, clean - dt * 3f)
            if (pz <= 0f && rnd.nextFloat() < dt * 5f) pvz = (120f + rnd.nextFloat() * 160f) * k
            if (rnd.nextFloat() < dt * 40f) { val q = rnd.nextFloat(); parts.add(Part(rnd.nextFloat() * W, gy + rnd.nextFloat() * (H - gy) * .7f, (q - .5f) * 30f * k, (-40f - q * 70f) * k, 150f * k, 1.2f, 1.2f, null, (2f + q * 3f) * k)) }
            if (shake > .85f && sleeping) { sleeping = false; joy = max(0f, joy - 10f) }
        }
        dizzy = max(0f, dizzy - dt); eat = max(0f, eat - dt); up = max(0f, up - dt)
        if (dizzy > 0f) beh = 0
        if (beh != 0 && !sleeping) {
            bt -= dt; if (bt <= 0f) { if (beh == 6) { dizzy = 1.5f; tx = -1f }; beh = 0; spdM = 1f }
            when (beh) {
                3 -> { if (pz <= 0f && pvz <= 0f) pvz = 170f * k; joy = min(100f, joy + dt * 1f); if (rnd.nextFloat() < dt * 3f) emoji("🎵", px + (rnd.nextFloat() - .5f) * rr * 2f, gy - rr * 2f, 0f, -45f * k, 1.2f, 18f) }
                5 -> { joy = min(100f, joy + dt * .4f); if (rnd.nextFloat() < dt * 25f) parts.add(Part(px + (rnd.nextFloat() - .5f) * rr, gy, (rnd.nextFloat() - .5f) * 70f * k, -60f * k, 200f * k, .8f, .8f, null, (1.5f + rnd.nextFloat() * 2f) * k)); if (rnd.nextFloat() < dt * .25f) { emoji("💎", px, gy - rr * 1.2f, 0f, -70f * k, 1.5f, 24f, 60f * k); joy = min(100f, joy + 5f); gems++ } }
                2 -> { energy = min(100f, energy + dt * .3f) }
                4 -> { up = 1f }
                12 -> {
                    if (pz <= 0f && pvz <= 0f) pvz = 100f * k
                    joy = max(0f, joy - dt * 3f)
                    if (rnd.nextFloat() < dt * 25f) parts.add(Part(px + (rnd.nextFloat() - .5f) * rr * 2f, gy, (rnd.nextFloat() - .5f) * 120f * k, -60f * k, 200f * k, .7f, .7f, null, (1.5f + rnd.nextFloat() * 2.5f) * k))
                    if (rnd.nextFloat() < dt * 2f) emoji("💢", px + (rnd.nextFloat() - .5f) * rr, gy - pz - rr * 2f, 0f, -30f * k, 1f, 24f)
                }
                6 -> { zt -= dt; if (zt <= 0f) { zt = .45f; tx = W * .1f + rnd.nextFloat() * W * .8f; td = rnd.nextFloat(); spdM = 3.5f } }
                7 -> { if (tx < 0f) { beh = 8; bt = 6f; act = bt } }
                8 -> { energy = min(100f, energy + dt * 3f) }
                9 -> { clean = max(0f, clean - dt * 12f); joy = min(100f, joy + dt * .8f); if (rnd.nextFloat() < dt * 30f) parts.add(Part(px + (rnd.nextFloat() - .5f) * rr * 2f, gy, (rnd.nextFloat() - .5f) * 80f * k, -50f * k, 180f * k, .9f, .9f, null, (1.5f + rnd.nextFloat() * 2f) * k)) }
                10 -> { up = 1f; if (rnd.nextFloat() < dt * 1.5f) hearts(1) }
                11 -> { if (rnd.nextFloat() < dt * 4f) { prints.add(Print(px + (rnd.nextFloat() - .5f) * rr * 2.5f, gy + rnd.nextFloat() * 8f * k, 1f)); if (prints.size > 40) prints.removeAt(0) }; joy = max(0f, joy - dt * .5f) }
            }
        }
        // meteors
        nextMeteor -= dt
        if (nextMeteor <= 0f) { nextMeteor = 15f + rnd.nextFloat() * 25f; mets.add(mkMeteor()) }
        val mi = mets.iterator()
        while (mi.hasNext()) {
            val m = mi.next(); m.x += m.vx * dt; m.y += m.vy * dt; m.life -= dt
            if (!m.seen && !sleeping) { m.seen = true; up = 2f; joy = min(100f, joy + 2f) }
            if (m.life <= 0f) mi.remove()
        }
        // behaviour
        if (!sleeping) {
            act -= dt; blink -= dt; if (blink < -.12f) blink = 2f + rnd.nextFloat() * 3f
            val f = foods.firstOrNull { it.landed }
            val b = ball
            val cr = crits.firstOrNull { it.st == 1 || it.st == 2 || it.st == 4 }
            if (beh == 12) tx = -1f
            else if (f != null) { tx = f.x; td = f.d; beh = 0; spdM = 1.6f }
            else if (b != null) { tx = b.x; td = b.d; beh = 0; spdM = 1.8f }
            else if (cr != null && dizzy <= 0f) { tx = cr.x; td = cr.d; beh = 0; spdM = 2.4f }
            else if (act <= 0f && dizzy <= 0f) {
                act = 1.2f + rnd.nextFloat() * 3f
                val q = rnd.nextFloat(); spdM = 1f; beh = 0
                val wx = W * .12f + rnd.nextFloat() * W * .76f; val wd = .05f + rnd.nextFloat() * .95f
                if (q < .2f) { tx = wx; td = wd }
                else if (q < .34f) { tx = wx; td = wd; spdM = 2.3f }
                else if (q < .4f && mood > 40f && pz <= 0f) pvz = 260f * k
                else if (q < .47f) { beh = 2; bt = 2f + rnd.nextFloat() * 2f; act = bt; tx = -1f }
                else if (q < .55f && mood > 45f) { beh = 3; bt = 2.5f; act = bt; tx = -1f }
                else if (q < .6f) { beh = 4; bt = 2f; act = bt; tx = -1f }
                else if (q < .66f) { beh = 5; bt = 2.5f; act = bt; tx = -1f }
                else if (q < .72f && energy > 30f) { beh = 6; bt = 3f; act = bt; zt = 0f; tx = -1f }
                else if (q < .77f) { beh = 7; bt = 14f; act = bt; tx = wx; td = wd }
                else if (q < .82f) { beh = 9; bt = 3f; act = bt; tx = -1f }
                else if (q < .87f) { beh = 10; bt = 3f; act = bt; tx = -1f }
                else if (q < .92f && System.currentTimeMillis() - lastCare > 1_200_000L) { beh = 11; bt = 8f; act = bt; tx = -1f }
                else tx = -1f
                look = (rnd.nextFloat() - .5f) * 2f
            }
            if (cr != null && beh != 12 && pz <= 0f && dizzy <= 0f && abs(cr.x - px) < 320f * k * scAt(pd) && abs(cr.d - pd) < .3f && rnd.nextFloat() < dt * 3f) pvz = 250f * k
            var mvx = 0f; var mvd = 0f
            if (tx >= 0f && dizzy <= 0f) {
                val dx = tx - px; val dd = td - pd
                val sp = (if (energy > 20f) 130f else 60f) * k * spdM * (if (mood < 30f) .7f else 1f)
                val nx = abs(dx) > 5f * k; val nd = abs(dd) > .03f
                if (!nx && !nd) tx = -1f
                else {
                    if (nx) { val st = sign(dx) * sp * scAt(pd) * dt; px += st; look = sign(dx); mvx = sign(dx); stepD += abs(st) }
                    if (nd) { pd += sign(dd) * .3f * dt * (sp / (130f * k)); mvd = sign(dd) }
                    pd = pd.coerceIn(0f, 1f)
                    if (stepD > 26f * k * scAt(pd)) { stepD = 0f; prints.add(Print(px, gy + (if (prints.size % 2 == 0) -2f else 6f) * k * scAt(pd), 1f)); if (prints.size > 40) prints.removeAt(0) }
                }
            }
            moving = mvx != 0f || mvd != 0f
            val away = beh == 11 || (tx >= 0f && mvd < 0f && abs(td - pd) > abs(tx - px) / W * .8f)
            fx += ((if (away) 0f else mvx) - fx) * min(1f, dt * 6f)
            fb += ((if (away) -1f else 1f) - fb) * min(1f, dt * 6f)
            if (beh == 0 && !moving) look = sin(t * .9f) * .8f
            px = (px + tilt * 45f * k * dt).coerceIn(rr, W - rr)
            if (menuOpen) {
                val lim = if (menuRight) W * .6f else W * .4f
                if (menuRight && px > lim) px -= 110f * k * dt else if (!menuRight && px < lim) px += 110f * k * dt
                if ((menuRight && tx > lim) || (!menuRight && tx in 0f..lim)) tx = -1f
            }
            if (f != null && abs(f.x - px) < rr * .7f && abs(f.d - pd) < .12f) {
                foods.remove(f); hunger = min(100f, hunger + 25f); joy = min(100f, joy + 3f); eat = 1.2f; tx = -1f
                repeat(6) { emoji("✨", px, gy - rr * .9f, (rnd.nextFloat() - .5f) * 90f * k, (-70f - rnd.nextFloat() * 50f) * k, .9f, 14f, 200f * k) }
            }
        }
        // low-gravity hop
        if (pz > 0f || pvz > 0f) { pvz -= 500f * k * dt; pz += pvz * dt; if (pz <= 0f) { pz = 0f; pvz = 0f } }
        // items
        for (f in foods) if (!f.landed) { f.vy += 260f * k * dt; f.y += f.vy * dt; if (f.y >= yAt(f.d) - 8f * k) { f.y = yAt(f.d) - 8f * k; f.landed = true } }
        val b = ball
        if (b != null) {
            b.life -= dt; b.vz -= 500f * k * dt; b.z += b.vz * dt; b.x += b.vx * dt
            if (b.z <= 0f) { b.z = 0f; b.vz = abs(b.vz) * .75f; if (b.vz < 40f * k) b.vz = 0f; b.vx *= .92f }
            if (b.x < 12f * k || b.x > W - 12f * k) { b.vx *= -1f; b.x = b.x.coerceIn(12f * k, W - 12f * k) }
            if (!sleeping && abs(b.x - px) < rr * .9f && b.z < rr * 1.2f && abs(b.d - pd) < .2f && dizzy <= 0f) {
                b.vx = (if (b.x >= px) 1f else -1f) * (160f + rnd.nextFloat() * 120f) * k; b.vz = (240f + rnd.nextFloat() * 120f) * k
                joy = min(100f, joy + 3f); energy = max(0f, energy - 2f); if (pz <= 0f) pvz = 180f * k; hearts(1)
            }
            if (b.life <= 0f) ball = null
        }
        // moon critters
        critT -= dt
        if (critT <= 0f && crits.size < 2 && !sleeping) { critT = 7f + rnd.nextFloat() * 12f; val sx = W * .1f + rnd.nextFloat() * W * .8f; val sd = .05f + rnd.nextFloat() * .95f; crits.add(Crit(sx, sd, rnd.nextFloat() < .12f)); holes.add(Hole(sx, sd)) }
        val ci = crits.iterator()
        while (ci.hasNext()) {
            val cr = ci.next(); cr.t += dt
            when (cr.st) {
                0 -> { if (cr.t > .6f) { cr.st = 1; cr.t = 0f } }
                1 -> {
                    cr.x += sin(t * 3f + cr.d * 10f) * 12f * k * dt
                    val dxp = cr.x - px
                    if (cr.t > 8f) { cr.st = 3; cr.t = 0f }
                    else if (!sleeping && abs(dxp) < 130f * k * scAt(cr.d) && abs(cr.d - pd) < .25f) { startFlee(cr, dxp) }
                }
                2 -> { cr.x += sign(cr.hx - cr.x) * cr.spd * k * scAt(cr.d) * dt
                    if (abs(cr.hx - cr.x) < 8f * k || cr.t > 3.5f) beginDig(cr) }
                4 -> {
                    val h = cr.hole; if (h != null) h.grow = (cr.t / cr.digT).coerceIn(0f, 1f)
                    if (rnd.nextFloat() < dt * 30f) parts.add(Part(cr.x + (rnd.nextFloat() - .5f) * 20f * k * scAt(cr.d), yAt(cr.d), (rnd.nextFloat() - .5f) * 60f * k, -50f * k, 160f * k, .7f, .7f, null, (1f + rnd.nextFloat() * 1.5f) * k * (.4f + scAt(cr.d))))
                    if (cr.t >= cr.digT) { cr.st = 3; cr.t = 0f; if (!sleeping && abs(cr.x - px) < 500f * k) { emoji("❓", px, gy - rr * 2.4f, 0f, -40f * k, 1.2f, 22f); misses++; if (misses >= 3 && beh != 12 && dizzy <= 0f) { beh = 12; bt = 3.8f; act = bt; tx = -1f; spdM = 1f; misses = 0 } } }
                }
                3 -> { if (cr.t > .5f) { ci.remove(); continue } }
            }
            if (!sleeping && dizzy <= 0f && (cr.st == 1 || cr.st == 2 || cr.st == 4) && abs(cr.x - px) < rr * .65f && abs(cr.d - pd) < .1f) {
                ci.remove(); val g = cr.gold
                hunger = min(100f, hunger + (if (g) 20f else 10f)); joy = min(100f, joy + (if (g) 10f else 5f)); if (g) gems++
                eat = 1.2f; tx = -1f; spdM = 1f; misses = 0
                repeat(6) { emoji("✨", px, gy - rr * .9f, (rnd.nextFloat() - .5f) * 90f * k, (-70f - rnd.nextFloat() * 50f) * k, .9f, 14f, 200f * k) }
            }
        }
        val hi = holes.iterator()
        while (hi.hasNext()) { val h = hi.next(); h.age += dt; if (h.age > h.life) hi.remove() }
        // UFO visitor
        ufoT -= dt
        if (ufoT <= 0f && ufo == null && !sleeping) {
            ufoT = 70f + rnd.nextFloat() * 60f; val l = rnd.nextBoolean()
            ufo = Ufo(if (l) -40f * k else W + 40f * k, H * (.25f + rnd.nextFloat() * .15f), (if (l) 1f else -1f) * 150f * k, rnd.nextFloat() < .4f)
            if (dizzy <= 0f) { beh = 10; bt = 4f; act = bt; tx = -1f }
        }
        val u = ufo
        if (u != null) {
            u.x += u.vx * dt
            if (u.will && !u.gift && abs(u.x - W / 2f) < 30f * k) { u.gift = true; foods.add(Food(u.x, u.y, .2f + rnd.nextFloat() * .8f)) }
            if (u.x < -80f * k || u.x > W + 80f * k) ufo = null
        }
        // particles / footprints
        val pi = parts.iterator()
        while (pi.hasNext()) { val p = pi.next(); p.x += p.vx * dt; p.y += p.vy * dt; p.vy += p.g * dt; p.life -= dt; if (p.life <= 0f) pi.remove() }
        val fi = prints.iterator()
        while (fi.hasNext()) { val p = fi.next(); p.a -= dt * .05f; if (p.a <= 0f) fi.remove() }
    }

    // ---- rendering ----
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()
    private val earthPath = Path()
    private val surf = Path()
    private var earthShader: Shader? = null
    private var shadeShader: Shader? = null
    private var groundShader: Shader? = null
    private var bodyShader: Shader? = null
    private var stars = FloatArray(0)
    private val craters = Random(5).let { r -> List(22) { floatArrayOf(r.nextFloat(), r.nextFloat(), .5f + r.nextFloat() * 1.2f) } }
    private val blobs = Random(3).let { r -> List(34) { i -> floatArrayOf(r.nextFloat() * 6.28f, (r.nextFloat() - .5f) * 2.2f, .05f + r.nextFloat() * .12f, if (i < 11) 1f else 0f) } }
    private val specks = floatArrayOf(-.4f, -.9f, .3f, -1.1f, .5f, -.6f, -.1f, -.45f, .15f, -1.35f, -.55f, -.5f)
    private val SIDES = floatArrayOf(-1f, 1f)

    fun resize(w: Int, h: Int) {
        W = w.toFloat(); H = h.toFloat()
        if (px == 0f) px = W / 2f
        val r = W * .11f
        bodyShader = RadialGradient(-r * .25f, -r * 1.2f, r * 1.2f, 0xFF9CF0C4.toInt(), 0xFF3AA488.toInt(), Shader.TileMode.CLAMP)
    }

    private fun pt(col: Int, style: Paint.Style = Paint.Style.FILL, sw: Float = 0f): Paint {
        paint.reset(); paint.isAntiAlias = true; paint.color = col; paint.style = style; paint.strokeWidth = sw; paint.strokeCap = Paint.Cap.ROUND
        return paint
    }
    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, a: Int = 255) {
        val p = pt(Color.WHITE); p.textSize = size; p.textAlign = Paint.Align.CENTER; p.alpha = a
        c.drawText(s, x, y + size * .35f, p)
    }

    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcR = Rect(); private val dstR = RectF()

    private fun drawFood(c: Canvas, f: Food) {
        val k = sc * scAt(f.d)
        c.drawCircle(f.x, f.y, 20f * k, pt(Color.argb(50, 210, 139, 255)))
        c.drawCircle(f.x, f.y, 10f * k, pt(Color.WHITE).also { it.shader = RadialGradient(f.x - 3f * k, f.y - 3f * k, 11f * k, 0xFFF0C8FF.toInt(), 0xFF8A3FD0.toInt(), Shader.TileMode.CLAMP) })
    }
    private fun drawBall(c: Canvas, b: Ball) {
        val k = sc * scAt(b.d); val g0 = yAt(b.d)
        val by = g0 - b.z * scAt(b.d) - 10f * k
        rf.set(b.x - 10f * k, g0 + 1f * k, b.x + 10f * k, g0 + 7f * k); c.drawOval(rf, pt(Color.argb(64, 0, 0, 0)))
        c.drawCircle(b.x, by, 12f * k, pt(Color.WHITE).also { it.shader = RadialGradient(b.x - 4f * k, by - 4f * k, 12f * k, 0xFFFFF6A8.toInt(), 0xFFE0A020.toInt(), Shader.TileMode.CLAMP) })
    }

    private fun startFlee(cr: Crit, dxp: Float) {
        val k = sc
        cr.st = 2; cr.t = 0f; cr.dir = if (dxp >= 0f) 1 else -1
        var tgt = cr.x + cr.dir * (220f + rnd.nextFloat() * 160f) * k * scAt(cr.d)
        if (tgt < 30f * k || tgt > W - 30f * k) { cr.dir = -cr.dir; tgt = cr.x + cr.dir * (220f + rnd.nextFloat() * 160f) * k * scAt(cr.d) }
        cr.hx = tgt.coerceIn(30f * k, W - 30f * k)
    }
    private fun beginDig(cr: Crit) {
        cr.st = 4; cr.t = 0f; cr.digT = 1.6f + rnd.nextFloat() * 1.2f
        val h = Hole(cr.x, cr.d); h.grow = 0f; h.life = cr.digT + 6f; cr.hole = h; holes.add(h)
    }
    private fun drawHole(c: Canvas, h: Hole) {
        val a = (minOf(1f, h.age / .4f) * minOf(1f, (h.life - h.age) / 2.5f)).coerceIn(0f, 1f)
        val s = sc * scAt(h.d) * h.grow.coerceAtLeast(.05f); val g0 = yAt(h.d)
        rf.set(h.x - 22f * s, g0 - 5f * s, h.x + 22f * s, g0 + 7f * s); c.drawOval(rf, pt(Color.argb((150f * a).toInt(), 25, 20, 18)))
        rf.set(h.x - 26f * s, g0 - 7f * s, h.x + 26f * s, g0 + 9f * s); c.drawArc(rf, 190f, 160f, false, pt(Color.argb((110f * a).toInt(), 220, 205, 185), Paint.Style.STROKE, 2f * s))
    }
    private fun drawCrit(c: Canvas, cr: Crit) {
        val s = sc * scAt(cr.d); val g0 = yAt(cr.d)
        val hs = (if (cr.st == 0) cr.t / .6f else if (cr.st == 3) 1f - cr.t / .5f else if (cr.st == 4) 1f - .3f * cr.t / cr.digT else 1f).coerceIn(0f, 1f)
        if (hs > .05f) {
            val w = 14f * s * hs; val bob = sin(t * 14f + cr.x) * 2f * s
            rf.set(cr.x - w, g0 - 26f * s * hs + bob, cr.x + w, g0 - 2f * s); c.drawOval(rf, pt((if (cr.gold) 0xFFFFD84A else 0xFF9A78C8).toInt()))
            val lk = sign(px - cr.x) * 1.2f * s
            for (e in SIDES) {
                c.drawCircle(cr.x + e * w * .4f, g0 - 17f * s * hs + bob, 3.4f * s * hs, pt(Color.WHITE))
                c.drawCircle(cr.x + e * w * .4f + lk, g0 - 17f * s * hs + bob, 1.6f * s * hs, pt(0xFF0C1A1A.toInt()))
            }
        }
    }
    private fun drawUfo(c: Canvas) {
        val u = ufo ?: return
        val s = 30f * sc
        c.drawCircle(u.x, u.y, s * 1.6f, pt(Color.argb(30, 150, 255, 200)))
        rf.set(u.x - s, u.y - s * .25f, u.x + s, u.y + s * .25f); c.drawOval(rf, pt(0xFFB8C4D0.toInt()))
        rf.set(u.x - s * .45f, u.y - s * .6f, u.x + s * .45f, u.y + s * .1f); c.drawArc(rf, 180f, 180f, true, pt(Color.argb(200, 140, 230, 255)))
        for (i in -1..1) c.drawCircle(u.x + i * s * .55f, u.y, s * .07f, pt(if (((t * 4f).toInt() + i) % 2 == 0) 0xFFFFE066.toInt() else 0xFFFF6A4A.toInt()))
    }

    fun draw(c: Canvas) {
        val k = sc
        c.drawColor(Color.BLACK)
        c.save()
        if (shake > .02f) c.translate((rnd.nextFloat() - .5f) * shake * 16f * k, (rnd.nextFloat() - .5f) * shake * 16f * k)
        val bm = bmp
        if (bm != null) {
            val vis = (W * bm.height / H).toInt().coerceAtMost(bm.width)
            val shift = (tilt * .5f + offX) * bm.width * .04f
            val sx = (bm.width * .496f - vis / 2f + shift).toInt().coerceIn(0, bm.width - vis)
            srcR.set(sx, 0, sx + vis, bm.height); dstR.set(-30f, -30f, W + 30f, H + 30f)
            c.drawBitmap(bm, srcR, dstR, bgPaint)
        }
        for (m in mets) {
            val a = (m.life.coerceIn(0f, 1f) * 255f).toInt()
            c.drawLine(m.x, m.y, m.x - m.vx * .18f, m.y - m.vy * .18f, pt(Color.argb(a, 255, 255, 255), Paint.Style.STROKE, 2.5f * k))
        }
        for (h in holes) drawHole(c, h)
        drawUfo(c)
        for (p in prints) { rf.set(p.x - 5f * k, p.y - 2f * k, p.x + 5f * k, p.y + 2f * k); c.drawOval(rf, pt(Color.argb((p.a * 90).toInt(), 30, 26, 22))) }
        val b = ball
        for (f in foods) if (f.d < pd) drawFood(c, f)
        for (cr in crits) if (cr.d < pd) drawCrit(c, cr)
        if (b != null && b.d < pd) drawBall(c, b)
        drawPet(c)
        for (f in foods) if (f.d >= pd) drawFood(c, f)
        for (cr in crits) if (cr.d >= pd) drawCrit(c, cr)
        if (b != null && b.d >= pd) drawBall(c, b)
        for (p in parts) {
            val a = (255f * (p.life / p.max).coerceIn(0f, 1f)).toInt()
            if (p.ch != null) txt(c, p.ch, p.x, p.y, p.s * k, a) else c.drawCircle(p.x, p.y, p.s, pt(Color.argb(a, 184, 175, 162)))
        }
        if (nightA > .01f) c.drawColor(Color.argb((128f * nightA).toInt(), 0, 6, 30))
        c.restore()
    }

    private fun drawPet(c: Canvas) {
        val r = W * .11f; val k = sc; val s0 = scAt(pd)
        val x = px; val y = gy - pz - (if (moving && pz <= 0f) abs(sin(t * 9f * min(spdM, 2f))) * r * .1f else 0f)
        val mood = (hunger + joy + energy) / 3f
        val sl = sleeping || beh == 8
        c.save(); c.translate(x, gy); c.scale(s0, s0); c.translate(-x, -gy)
        if (beh == 8) { rf.set(x - r * 1.5f, gy - r * .12f, x + r * 1.5f, gy + r * .3f); c.drawOval(rf, pt(Color.argb(90, 30, 26, 22))) }
        val sw = r * (.9f - pz / (300f * k)).coerceIn(.3f, .9f)
        rf.set(x - sw, gy + 4f * k - r * .2f, x + sw, gy + 4f * k + r * .2f); c.drawOval(rf, pt(Color.argb(76, 0, 0, 0)))
        if (glow > 0f) c.drawCircle(x, y - r * .9f, r * 1.5f, pt(Color.argb((glow * 45f).toInt(), 255, 224, 102)))
        val shiver = if (battery < 15 && !charging && !sl) sin(t * 40f) * 1.5f * k else 0f
        c.save(); c.translate(x + shiver, y)
        var sq = 1f + sin(t * (if (sl) 1.5f else 3.2f)) * (if (sl) .05f else .03f)
        if (pz > 0f) sq = 1f + (pvz / (900f * k)).coerceIn(-.15f, .15f)
        else if (moving) sq = 1f + sin(t * 12f * min(spdM, 2f)) * .07f
        sq *= 1f - sleepBlend * .1f
        c.rotate(Math.toDegrees((if (dizzy > 0f) sin(t * 14f) * .14f else if (beh == 3) sin(t * 9f) * .28f else if (beh == 12) sin(t * 30f) * .12f else tilt * .06f + fx * .05f).toDouble()).toFloat())
        if (beh == 2 || beh == 11) sq = .82f + sin(t * 2f) * .02f
        if (beh == 8) sq = .8f + sin(t * 1.5f) * .04f
        if (beh == 9) { c.translate(0f, -r * .8f); c.rotate(180f + sin(t * 12f) * 18f); c.translate(0f, r * .8f) }
        if (beh == 5) sq = .88f + abs(sin(t * 9f)) * .1f
        c.scale(1f / sq, sq)
        val green = 0xFF2F8F78.toInt()
        for (s in SIDES) { rf.set(s * r * .42f - r * .28f, -r * .22f, s * r * .42f + r * .28f, -r * .06f); c.drawOval(rf, pt(green)) }
        // antennae
        val w = sin(t * 2.5f) * r * .08f; val low = hunger < 30f
        for (s in SIDES) {
            val ax = s * r * .62f + w * s; val ay = -r * 2.15f
            path.reset(); path.moveTo(s * r * .3f, -r * 1.5f); path.quadTo(s * r * .55f, -r * 1.9f, ax, ay)
            c.drawPath(path, pt(green, Paint.Style.STROKE, r * .07f))
            val pulse = .7f + .3f * sin(t * 4f + s)
            c.drawCircle(ax, ay, r * .3f * pulse, pt(if (low) Color.argb(60, 255, 106, 74) else Color.argb(60, 255, 157, 226)))
            c.drawCircle(ax, ay, r * .12f, pt(if (low) 0xFFFF6A4A.toInt() else 0xFFFFB3EC.toInt()))
        }
        // body
        rf.set(-r * .88f, -r * 1.65f, r * .88f, -r * .05f); c.drawOval(rf, pt(Color.WHITE).also { it.shader = bodyShader })
        rf.set(-r * .5f, -r * .88f, r * .5f, -r * .12f); c.drawOval(rf, pt(Color.argb(46, 255, 255, 255)))
        if (beh == 12) { rf.set(-r * .88f, -r * 1.65f, r * .88f, -r * .05f); c.drawOval(rf, pt(Color.argb(95, 255, 50, 40))) }
        for (s in SIDES) { val ay = if (beh == 10) -r * 1.3f + sin(t * 10f + s) * r * .15f else -r * .7f + sin(t * 3f + s) * r * .04f; rf.set(s * r * .9f - r * .16f, ay - r * .1f, s * r * .9f + r * .16f, ay + r * .1f); c.drawOval(rf, pt(0xFF3AA488.toInt())) }
        if (clean < 40f) { val a = ((40f - clean) / 40f * 200f).toInt(); var j = 0; while (j < specks.size) { c.drawCircle(specks[j] * r, specks[j + 1] * r, r * .06f, pt(Color.argb(a, 70, 60, 50))); j += 2 } }
        // eyes
        val lx = look * r * .07f; val ly = if (up > 0f) -r * .09f else 0f
        val ink = 0xFF0C1A1A.toInt()
        val back = fb < -.3f && abs(fx) < .5f
        for (s in SIDES) {
            val ex = s * r * .38f * (1f - .45f * abs(fx)) + fx * r * .28f; val ey = -r * 1.02f
            if (back) { rf.set(0f, 0f, 0f, 0f) } else if ((sl && sleepBlend > .35f) || blink < 0f) { rf.set(ex - r * .2f, ey - r * .2f, ex + r * .2f, ey + r * .2f); c.drawArc(rf, 18f, 144f, false, pt(ink, Paint.Style.STROKE, r * .06f)) }
            else if (dizzy > 0f) {
                path.reset(); var a = 0f
                while (a < 14f) { val rad = r * .02f * a; path.lineTo(ex + cos(a + t * 10f) * rad, ey + sin(a + t * 10f) * rad); a += .4f }
                c.drawPath(path, pt(ink, Paint.Style.STROKE, r * .045f))
            } else {
                rf.set(ex - r * .22f, ey - r * .28f * (if (mood < 30f) .8f else 1f), ex + r * .22f, ey + r * .28f * (if (mood < 30f) .8f else 1f)); c.drawOval(rf, pt(ink))
                c.drawCircle(ex - r * .07f + lx, ey - r * .1f + ly, r * .07f, pt(Color.WHITE))
                c.drawCircle(ex + r * .08f + lx, ey + r * .08f + ly, r * .035f, pt(Color.WHITE))
            }
        }
        if (beh == 12 && !back) for (s in SIDES) { val ex = s * r * .38f * (1f - .45f * abs(fx)) + fx * r * .28f; c.drawLine(ex + s * r * .26f, -r * 1.46f, ex - s * r * .2f, -r * 1.24f, pt(ink, Paint.Style.STROKE, r * .08f)) }
        // mouth
        val ms = r * .06f
        c.save(); c.translate(fx * r * .2f, 0f)
        if (back) {
            rf.set(-r * .62f, -r * .78f, r * .02f, -r * .12f); c.drawOval(rf, pt(0xFF84DDB5.toInt()))
            rf.set(-r * .02f, -r * .78f, r * .62f, -r * .12f); c.drawOval(rf, pt(0xFF84DDB5.toInt()))
            c.drawLine(0f, -r * .72f, 0f, -r * .2f, pt(0xFF3AA488.toInt(), Paint.Style.STROKE, r * .04f))
            val wag = sin(t * (if (moving) 32f else 9f)) * (if (moving) .35f else .22f)
            c.save(); c.translate(0f, -r * .55f); c.rotate(Math.toDegrees(wag.toDouble()).toFloat())
            rf.set(-r * .1f, -r * .3f, r * .1f, r * .02f); c.drawOval(rf, pt(0xFFB6F5D3.toInt()))
            c.restore()
        }
        else if (beh == 12) { rf.set(-r * .2f, -r * .72f, r * .2f, -r * .5f + abs(sin(t * 20f)) * r * .12f); c.drawOval(rf, pt(ink)) }
        else if (eat > 0f) { val h = r * .06f + abs(sin(t * 14f)) * r * .1f; rf.set(-r * .14f, -r * .55f - h, r * .14f, -r * .55f + h); c.drawOval(rf, pt(ink)) }
        else if (dizzy > 0f) { path.reset(); path.moveTo(-r * .15f, -r * .55f); for (n in 1..4) path.lineTo(-r * .15f + n * r * .075f, -r * .55f + (if (n % 2 == 1) -1f else 1f) * r * .04f); c.drawPath(path, pt(ink, Paint.Style.STROKE, ms)) }
        else if (sl && sleepBlend < .6f) { rf.set(-r * .12f, -r * .62f, r * .12f, -r * .4f); c.drawOval(rf, pt(ink)) }
        else if (sl) c.drawCircle(0f, -r * .58f, r * .07f, pt(ink, Paint.Style.STROKE, ms))
        else if (mood > 60f) { rf.set(-r * .2f, -r * .82f, r * .2f, -r * .42f); c.drawArc(rf, 27f, 126f, false, pt(ink, Paint.Style.STROKE, ms)) }
        else if (mood > 35f) c.drawLine(-r * .12f, -r * .55f, r * .12f, -r * .55f, pt(ink, Paint.Style.STROKE, ms))
        else { rf.set(-r * .18f, -r * .58f, r * .18f, -r * .22f); c.drawArc(rf, 207f, 126f, false, pt(ink, Paint.Style.STROKE, ms)) }
        c.restore()
        c.restore()
        // thought bubble / zzz
        if (!sl && dizzy <= 0f) {
            val n = if (battery < 15 && !charging) "🪫" else if (hunger < 30f) "🍇" else if (joy < 30f) "🎾" else if (energy < 25f) "💤" else if (clean < 30f) "🫧" else null
            if (n != null) {
                val bx = x + r * 1.2f; val by = y - r * 2.7f + sin(t * 2f) * 3f
                c.drawCircle(bx, by, r * .32f, pt(Color.argb(230, 255, 255, 255))); c.drawCircle(bx - r * .35f, by + r * .38f, r * .08f, pt(Color.argb(230, 255, 255, 255)))
                txt(c, n, bx, by, r * .4f)
            }
        }
        if (sl) for (n in 0..2) { val kk = (t * .4f + n / 3f) % 1f; txt(c, "z", x + r * .5f + kk * r * .6f, y - r * 2f - kk * r * .9f, r * .25f + n * 5f, ((1f - kk) * 255f).toInt()) }
        c.restore()
    }
}
