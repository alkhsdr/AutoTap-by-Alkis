package gr.autotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class TapService : AccessibilityService() {

    companion object {
        var instance: TapService? = null
        private val TEAL = Color.rgb(15, 85, 112)       // κύριο χρώμα (όπως Auto Clicker)
        private val BLUE = Color.rgb(13, 92, 122)
        private val LIGHT = Color.rgb(40, 150, 210)
        private val GREY = Color.rgb(170, 170, 170)
    }

    enum class StopMode { INFINITE, TIME, CYCLES }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val rnd = Random()

    // ---- δεδομένα ----
    private val steps = mutableListOf<Step>()
    private var stopMode = StopMode.CYCLES
    private var cycles = 1
    private var timeLimitSec = 60L
    private var loopGapMs = 0L

    // ---- κατάσταση ----
    private var recording = false
    private var playing = false
    private var paused = false
    private var markersVisible = true
    private var collapsed = false

    // ---- παράθυρα ----
    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var pill: LinearLayout
    private lateinit var pillLp: WindowManager.LayoutParams
    private lateinit var tvLoop: TextView
    private lateinit var tvStep: TextView
    private lateinit var tvTime: TextView
    private lateinit var ivStop: ImageView
    private lateinit var ivPause: ImageView
    private lateinit var lines: LinesView
    private var captureView: View? = null
    private lateinit var btnPlay: ImageView
    private lateinit var btnRec: ImageView
    private lateinit var btnEye: ImageView
    private lateinit var btnCollapse: ImageView
    private val panelButtons = mutableListOf<View>()

    private class Marker(val view: TargetView, val lp: WindowManager.LayoutParams)
    private val markers = mutableListOf<Pair<Marker, Marker?>>()   // αρχή, τέλος (σύρσιμο)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val targetSize get() = dp(60)
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    // ======================================================================
    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildLines()
        buildPill()
        buildPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopPlay() }

    override fun onDestroy() {
        stopPlay(); removeAll(); instance = null
        super.onDestroy()
    }

    // ======================================================================
    // Βοηθητικά
    private fun params(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun setTouchable(lp: WindowManager.LayoutParams, v: View, touchable: Boolean) {
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

    private fun icon(ctx: Context, res: Int, sizeDp: Int, color: Int = TEAL, padDp: Int = 0) = ImageView(ctx).apply {
        setImageResource(res); setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    /** Αν το δάχτυλο κινηθεί, μετακινεί το παράθυρο· αλλιώς είναι κλικ. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragOrClick(v: View, window: View, lp: WindowManager.LayoutParams, onClick: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; v.alpha = 0.5f }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); runCatching { wm.updateViewLayout(window, lp) } }
                }
                MotionEvent.ACTION_UP -> { v.alpha = 1f; if (!moved) onClick() }
                MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            true
        }
    }

    private fun removeAll() {
        clearMarkers()
        captureView?.let { runCatching { wm.removeView(it) } }; captureView = null
        if (::panel.isInitialized) runCatching { wm.removeView(panel) }
        if (::pill.isInitialized) runCatching { wm.removeView(pill) }
        if (::lines.isInitialized) runCatching { wm.removeView(lines) }
    }

    private fun bringControlsToFront() {
        runCatching { wm.removeView(pill) }; wm.addView(pill, pillLp)
        runCatching { wm.removeView(panel) }; wm.addView(panel, panelLp)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ======================================================================
    // Γραμμές συρσιμάτων
    private fun buildLines() {
        lines = LinesView(this) { if (markersVisible) steps else emptyList() }
        wm.addView(lines, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false))
    }

    // ======================================================================
    // Ταμπλό πάνω:  ■ | ❚❚ | ⟳ 00   ⇄ 00/08
    //                          00 : 00 : 00
    private fun buildPill() {
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(18), dp(8))
            background = rounded(Color.WHITE, 14)
            elevation = dp(6).toFloat()
        }
        fun divider() = View(this).apply {
            setBackgroundColor(Color.rgb(120, 120, 120))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(36)).apply { leftMargin = dp(10); rightMargin = dp(10) }
        }
        ivStop = icon(this, R.drawable.ic_stop, 40, GREY, 4)
        ivPause = icon(this, R.drawable.ic_pause, 40, GREY, 4)
        pill.addView(ivStop); pill.addView(divider()); pill.addView(ivPause); pill.addView(divider())

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tvLoop = TextView(this).apply { textSize = 15f; setTextColor(Color.BLACK); setPadding(dp(4), 0, dp(10), 0) }
        tvStep = TextView(this).apply { textSize = 15f; setTextColor(Color.BLACK); setPadding(dp(4), 0, 0, 0) }
        row.addView(icon(this, R.drawable.ic_repeat, 20, Color.BLACK)); row.addView(tvLoop)
        row.addView(View(this).apply { setBackgroundColor(GREY); layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply { rightMargin = dp(8) } })
        row.addView(icon(this, R.drawable.ic_swap, 20, Color.BLACK)); row.addView(tvStep)
        tvTime = TextView(this).apply { textSize = 24f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD }
        col.addView(row); col.addView(tvTime)
        pill.addView(col)

        pillLp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            x = screenW() / 2 - dp(140); y = dp(60)
        }
        dragOrClick(ivStop, pill, pillLp) { stopPlay() }
        dragOrClick(ivPause, pill, pillLp) { togglePause() }
        dragOrClick(col, pill, pillLp) { }
        wm.addView(pill, pillLp)
        updatePill()
    }

    private var playStart = 0L
    private var pausedAt = 0L
    private var curLoop = 0
    private var curStep = 0

    private fun elapsed(): Long = when {
        playStart == 0L -> 0L
        paused -> pausedAt - playStart
        else -> SystemClock.elapsedRealtime() - playStart
    }

    private fun updatePill() {
        if (!::pill.isInitialized) return
        val s = elapsed() / 1000
        tvLoop.text = String.format(Locale.US, "%02d", curLoop)
        tvStep.text = String.format(Locale.US, "%02d / %02d", curStep, steps.size)
        tvTime.text = String.format(Locale.US, "%02d : %02d : %02d", s / 3600, (s / 60) % 60, s % 60)
        pill.alpha = if (playing || recording) 1f else 0.75f
        ivStop.setColorFilter(if (playing) Color.rgb(200, 40, 40) else GREY)
        ivPause.setColorFilter(if (playing) (if (paused) LIGHT else TEAL) else GREY)
        ivPause.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            updatePill()
            if (stopMode == StopMode.TIME && !paused && elapsed() >= timeLimitSec * 1000) {
                stopPlay(); toast("Τέλος χρόνου"); return
            }
            ui.postDelayed(this, 250)
        }
    }

    // ======================================================================
    // Πλωτή μπάρα:  ▶ ◎ + − ↗ 💾 ⚙ 👁 ✕ ⌃
    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = rounded(Color.WHITE, 16)
            elevation = dp(6).toFloat()
        }
        panelLp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            x = screenW() - dp(66); y = screenH() / 3
        }
        fun btn(res: Int, onClick: () -> Unit): ImageView =
            icon(this, res, 52, TEAL, 11).also { dragOrClick(it, panel, panelLp, onClick) }

        btnPlay = btn(R.drawable.ic_play) { if (playing) stopPlay() else startPlay() }
        btnRec = btn(R.drawable.ic_rec) { toggleRec() }
        val btnAdd = btn(R.drawable.ic_add) { addStep(Step(Step.Type.TAP, screenW() / 2f, screenH() / 2f)) }
        val btnRemove = btn(R.drawable.ic_remove) {
            if (!playing && steps.isNotEmpty()) { steps.removeAt(steps.size - 1); rebuildMarkers() }
        }
        val btnSwipe = btn(R.drawable.ic_swipe) {
            val cx = screenW() / 2f; val cy = screenH() / 2f
            addStep(Step(Step.Type.SWIPE, cx, cy + dp(120), cx, cy - dp(120), durationMs = 300))
        }
        val btnSave = btn(R.drawable.ic_save) { showSaveDialog() }
        val btnSettings = btn(R.drawable.ic_settings) { showGlobalSettings() }
        btnEye = btn(R.drawable.ic_eye) { toggleEye() }
        val btnClose = btn(R.drawable.ic_close) { confirmClose() }
        btnCollapse = btn(R.drawable.ic_expand_less) { toggleCollapse() }

        panelButtons.addAll(listOf(btnRec, btnAdd, btnRemove, btnSwipe, btnSave, btnSettings, btnEye, btnClose))
        panel.addView(btnPlay)
        panelButtons.forEach { panel.addView(it) }
        panel.addView(btnCollapse)
        wm.addView(panel, panelLp)
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        panelButtons.forEach { it.visibility = if (collapsed) View.GONE else View.VISIBLE }
        btnCollapse.setImageResource(if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
    }

    private fun toggleEye() {
        markersVisible = !markersVisible
        btnEye.alpha = if (markersVisible) 1f else 0.35f
        applyMarkerVisibility()
        lines.invalidate()
    }

    private fun applyMarkerVisibility() {
        val touch = markersVisible && !playing
        for ((a, b) in markers) for (m in listOfNotNull(a, b)) {
            m.view.visibility = if (markersVisible) View.VISIBLE else View.INVISIBLE
            setTouchable(m.lp, m.view, touch)
        }
    }

    // ======================================================================
    // Αριθμημένοι στόχοι
    private fun clearMarkers() {
        for ((a, b) in markers) for (m in listOfNotNull(a, b)) runCatching { wm.removeView(m.view) }
        markers.clear()
    }

    private fun rebuildMarkers() {
        clearMarkers()
        steps.forEachIndexed { i, s ->
            markers.add(makeMarker(i, s, false) to (if (s.type == Step.Type.SWIPE) makeMarker(i, s, true) else null))
        }
        applyMarkerVisibility()
        lines.invalidate()
        bringControlsToFront()
        updatePill()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeMarker(index: Int, s: Step, isEnd: Boolean): Marker {
        val size = targetSize
        val v = TargetView(this, (index + 1).toString(), isEnd)
        val px = if (isEnd) s.x2 else s.x
        val py = if (isEnd) s.y2 else s.y
        val lp = params(size, size, true).apply { x = (px - size / 2).toInt(); y = (py - size / 2).toInt() }
        wm.addView(v, lp)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var dx = 0f; var dy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx; val my = e.rawY - dy
                    if (abs(mx) > slop || abs(my) > slop) moved = true
                    if (moved) {
                        lp.x = ox + mx.toInt(); lp.y = oy + my.toInt()
                        wm.updateViewLayout(v, lp)
                        setStepPoint(s, isEnd, lp.x + size / 2f, lp.y + size / 2f)
                        lines.invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showStepDialog(steps.indexOf(s))
                    else {
                        val loc = IntArray(2); v.getLocationOnScreen(loc)
                        setStepPoint(s, isEnd, loc[0] + size / 2f, loc[1] + size / 2f)
                        lines.invalidate()
                    }
                }
            }
            true
        }
        return Marker(v, lp)
    }

    private fun setStepPoint(s: Step, isEnd: Boolean, x: Float, y: Float) {
        if (isEnd) { s.x2 = x; s.y2 = y } else {
            if (s.type == Step.Type.TAP) { s.x2 = x; s.y2 = y }
            s.x = x; s.y = y
        }
    }

    private fun addStep(s: Step) {
        if (playing) return
        steps.add(s); rebuildMarkers()
    }

    // ======================================================================
    // Επιφάνεια που «πιάνει» αγγίγματα (για REC και για επιλογή θέσης)
    @SuppressLint("ClickableViewAccessibility")
    private fun addCapture(msg: String, tint: Int, onTouch: (MotionEvent) -> Unit) {
        removeCapture()
        val cv = TextView(this).apply {
            setBackgroundColor(tint); text = msg
            setTextColor(Color.WHITE); textSize = 15f
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), 0, dp(20), dp(60))
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setOnTouchListener { _, e -> onTouch(e); true }
        }
        captureView = cv
        wm.addView(cv, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
    }

    private fun removeCapture() {
        captureView?.let { runCatching { wm.removeView(it) } }; captureView = null
    }

    // ======================================================================
    // REC
    private var lastRecTime = 0L

    private fun toggleRec() {
        if (playing) return
        if (recording) {
            recording = false
            removeCapture()
            btnRec.setColorFilter(TEAL)
            updatePill()
            toast("Καταγράφηκαν ${steps.size} βήματα")
            return
        }
        recording = true
        lastRecTime = 0L
        btnRec.setColorFilter(Color.rgb(220, 30, 30))
        var downX = 0f; var downY = 0f; var downT = 0L
        addCapture("● REC — άγγιξε ή σύρε με τη σειρά που θέλεις.\nΠάτα ξανά ◎ για τέλος.", Color.argb(40, 255, 0, 0)) { e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; downT = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val upT = SystemClock.uptimeMillis()
                    val press = (upT - downT).coerceIn(30L, 5000L)
                    val delay = if (lastRecTime == 0L) 500L else (downT - lastRecTime).coerceAtLeast(50L)
                    lastRecTime = upT
                    val s = if (hypot(e.rawX - downX, e.rawY - downY) > dp(25))
                        Step(Step.Type.SWIPE, downX, downY, e.rawX, e.rawY, delay, 1, press.coerceAtLeast(100L))
                    else
                        Step(Step.Type.TAP, downX, downY, downX, downY, delay, 1, min(press, 150L))
                    steps.add(s); rebuildMarkers()
                }
            }
        }
        rebuildMarkers()
    }

    // ======================================================================
    // Εκτέλεση
    private var repIndex = 0
    private var gen = 0   // ακυρώνει παλιές προγραμματισμένες ενέργειες

    private fun startPlay() {
        if (recording) toggleRec()
        if (steps.isEmpty()) { toast("Δεν υπάρχουν βήματα. Πάτα ◎ (REC) ή +"); return }
        playing = true; paused = false; gen++
        curLoop = 1; curStep = 0; repIndex = 0
        playStart = SystemClock.elapsedRealtime()
        btnPlay.setImageResource(R.drawable.ic_stop); btnPlay.setColorFilter(Color.rgb(200, 40, 40))
        applyMarkerVisibility()
        ui.post(ticker)
        scheduleStep(steps[0].delayMs)
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false; paused = false; gen++
        ui.removeCallbacksAndMessages(null)
        markers.forEach { (a, b) -> a.view.active = false; b?.view?.active = false }
        if (::btnPlay.isInitialized) { btnPlay.setImageResource(R.drawable.ic_play); btnPlay.setColorFilter(TEAL) }
        applyMarkerVisibility()
        updatePill()
        playStart = 0L; curLoop = 0; curStep = 0
    }

    private fun togglePause() {
        if (!playing) return
        gen++
        if (!paused) { paused = true; pausedAt = SystemClock.elapsedRealtime() }
        else {
            paused = false
            playStart += SystemClock.elapsedRealtime() - pausedAt   // ο χρόνος παύσης δεν μετράει
            ui.removeCallbacks(ticker); ui.post(ticker)
            scheduleStep(0)
        }
        updatePill()
    }

    private fun scheduleStep(delay: Long) {
        val g = gen
        val anti = steps.getOrNull(curStep)?.antiDetect == true
        ui.postDelayed({ if (g == gen) runCurrent() }, jitterTime(delay, anti, 0.20))
    }

    private fun runCurrent() {
        if (!playing || paused) return
        val s = steps.getOrNull(curStep) ?: return stopPlay()
        markers.forEachIndexed { i, (a, b) -> a.view.active = i == curStep; b?.view?.active = i == curStep }
        val shown = curStep; curStep = shown + 1; updatePill(); curStep = shown

        val g = gen
        val ok = dispatchGesture(buildGesture(s), object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ui.post { if (g == gen) advance() } }
            override fun onCancelled(d: GestureDescription?) { ui.post { if (g == gen) advance() } }
        }, null)
        if (!ok) ui.postDelayed({ if (g == gen) advance() }, 100)
    }

    private fun advance() {
        if (!playing) return
        val s = steps[curStep]
        repIndex++
        if (repIndex < max(1, s.repeat)) { scheduleStep(s.delayMs); return }
        repIndex = 0
        curStep++
        if (curStep >= steps.size) {
            curStep = 0
            if (stopMode == StopMode.CYCLES && curLoop >= cycles) { stopPlay(); toast("Ολοκληρώθηκε"); return }
            curLoop++
            val g = gen
            ui.postDelayed({ if (playing && g == gen) scheduleStep(steps[0].delayMs) }, loopGapMs)
            return
        }
        scheduleStep(steps[curStep].delayMs)
    }

    // ---- Αντι-ανίχνευση ----
    private fun jitterTime(ms: Long, anti: Boolean, pct: Double): Long {
        if (!anti || ms <= 0) return max(0L, ms)
        return max(1L, (ms * (1 + (rnd.nextDouble() * 2 - 1) * pct)).toLong())
    }

    private fun jitterPos(v: Float, anti: Boolean): Float =
        if (!anti) v else v + (rnd.nextGaussian() * dp(4)).toFloat().coerceIn(-dp(10).toFloat(), dp(10).toFloat())

    private fun buildGesture(s: Step): GestureDescription {
        val a = s.antiDetect
        val path = Path()
        val x1 = jitterPos(s.x, a).coerceIn(0f, screenW() - 1f)
        val y1 = jitterPos(s.y, a).coerceIn(0f, screenH() - 1f)
        path.moveTo(x1, y1)
        if (s.type == Step.Type.SWIPE) {
            val x2 = jitterPos(s.x2, a).coerceIn(0f, screenW() - 1f)
            val y2 = jitterPos(s.y2, a).coerceIn(0f, screenH() - 1f)
            if (a) {
                val cx = (x1 + x2) / 2 + (rnd.nextGaussian() * dp(12)).toFloat()
                val cy = (y1 + y2) / 2 + (rnd.nextGaussian() * dp(12)).toFloat()
                path.quadTo(cx, cy, x2, y2)
            } else path.lineTo(x2, y2)
        } else if (a) {
            path.lineTo(x1 + rnd.nextInt(3) - 1, y1 + rnd.nextInt(3) - 1)
        }
        val dur = jitterTime(s.durationMs, a, 0.25).coerceIn(1L, GestureDescription.getMaxGestureDuration() - 1)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
    }

    // ======================================================================
    // Διάλογοι
    private fun dialogCtx(): Context =
        ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    private fun showOverlayDialog(d: AlertDialog) {
        d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.show()
    }

    /** Γραμμή:  [εικονίδιο]  Τίτλος
     *                         1179 ______________ χλστ. δ. */
    private fun fieldRow(ctx: Context, parent: LinearLayout, iconRes: Int, label: String, unit: String): EditText {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(icon(ctx, iconRes, 52, TEAL, 8).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply { text = label; textSize = 19f; setTextColor(Color.BLACK) })
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; textSize = 20f; setTextColor(Color.BLACK)
            backgroundTintList = ColorStateList.valueOf(TEAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inner.addView(et)
        inner.addView(TextView(ctx).apply { text = unit; textSize = 15f; setTextColor(Color.DKGRAY) })
        col.addView(inner)
        row.addView(col)
        parent.addView(row)
        return et
    }

    /** «Κάντε κλικ στις ρυθμίσεις» – ρυθμίσεις κάθε βήματος. */
    private fun showStepDialog(startIndex: Int) {
        if (startIndex !in steps.indices || playing) return
        val ctx = dialogCtx()
        var idx = startIndex
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), 0) }
        root.addView(top)

        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(ctx).apply {
            text = "Κάντε κλικ στις ρυθμίσεις"; textSize = 21f; setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvType = TextView(ctx).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(8), dp(3), dp(8), dp(3)) }
        titleRow.addView(tvType)
        top.addView(titleRow)
        top.addView(TextView(ctx).apply {
            text = "Αγγίξτε έναν αριθμό για να αλλάξετε βήμα"; textSize = 14f; setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(6))
        })
        val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.rgb(225, 225, 225))
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8) }
        })

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, dp(22), dp(8)) }
        val etDelay = fieldRow(ctx, body, R.drawable.ic_timer, "Χρόνος καθυστέρησης", "χλστ. δ.")
        val etRepeat = fieldRow(ctx, body, R.drawable.ic_repeat, "Αριθμός επαναλήψεων", "Φορές")
        val etDur = fieldRow(ctx, body, R.drawable.ic_hourglass, "Διάρκεια", "χλστ. δ.")
        val cbAnti = CheckBox(ctx).apply {
            text = "Αντί-ανίχνευση"; textSize = 19f; setTextColor(Color.BLACK)
            buttonTintList = ColorStateList.valueOf(TEAL)
            setPadding(dp(18), dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8); topMargin = dp(8) }
        }
        body.addView(cbAnti)
        root.addView(ScrollView(ctx).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })

        fun saveCurrent() {
            val s = steps.getOrNull(idx) ?: return
            s.delayMs = etDelay.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: s.delayMs
            s.repeat = etRepeat.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: s.repeat
            s.durationMs = etDur.text.toString().toLongOrNull()?.coerceIn(1L, 59_000L) ?: s.durationMs
            s.antiDetect = cbAnti.isChecked
        }

        lateinit var load: () -> Unit
        load = {
            tabs.removeAllViews()
            tabs.addView(TextView(ctx).apply { text = "#"; textSize = 20f; setTextColor(Color.GRAY); setPadding(0, 0, dp(6), 0) })
            steps.indices.forEach { i ->
                tabs.addView(TextView(ctx).apply {
                    text = (i + 1).toString(); textSize = 21f; gravity = Gravity.CENTER
                    setTextColor(if (i == idx) TEAL else LIGHT)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(46)).apply { rightMargin = dp(4) }
                    if (i == idx) background = rounded(Color.TRANSPARENT, 8, TEAL)
                    setOnClickListener { saveCurrent(); idx = i; load() }
                })
            }
            steps.getOrNull(idx)?.let { s ->
                val swipe = s.type == Step.Type.SWIPE
                tvType.text = if (swipe) "Σύρσιμο" else "Πάτημα"
                tvType.background = rounded(if (swipe) Color.rgb(240, 150, 30) else LIGHT, 10)
                etDelay.setText(s.delayMs.toString())
                etRepeat.setText(s.repeat.toString())
                etDur.setText(s.durationMs.toString())
                cbAnti.isChecked = s.antiDetect
            }
        }

        // κάτω γραμμή εικονιδίων: ⧉  📍  ⇄  🗑                 ✓
        lateinit var dlg: AlertDialog
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(14))
            setBackgroundColor(Color.WHITE)
        }
        fun barIcon(res: Int, onClick: () -> Unit) = icon(ctx, res, 56, Color.BLACK, 12).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)
            setOnClickListener { onClick() }
        }
        bar.addView(barIcon(R.drawable.ic_duplicate) {          // Αντιγραφή βήματος
            saveCurrent(); steps.add(idx + 1, steps[idx].copy()); idx++; rebuildMarkers(); load()
            toast("Αντιγράφηκε ως βήμα ${idx + 1}")
        })
        bar.addView(barIcon(R.drawable.ic_location) {           // Νέα θέση με άγγιγμα
            saveCurrent(); val i = idx; dlg.dismiss(); pickLocation(i)
        })
        bar.addView(barIcon(R.drawable.ic_swap) {               // Αλλαγή σειράς με το προηγούμενο
            saveCurrent()
            if (idx > 0) { Collections.swap(steps, idx, idx - 1); idx--; rebuildMarkers(); load() }
            else toast("Είναι ήδη πρώτο")
        })
        bar.addView(barIcon(R.drawable.ic_delete) {             // Διαγραφή
            steps.removeAt(idx); rebuildMarkers(); dlg.dismiss()
        })
        bar.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        bar.addView(icon(ctx, R.drawable.ic_check, 56, Color.BLACK, 10).apply {
            background = rounded(Color.WHITE, 12, Color.BLACK)
            setOnClickListener { saveCurrent(); rebuildMarkers(); dlg.dismiss() }
        })
        root.addView(View(ctx).apply { setBackgroundColor(Color.rgb(225, 225, 225)); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) })
        root.addView(bar)

        load()
        dlg = AlertDialog.Builder(ctx).setView(root).create()
        dlg.setOnCancelListener { saveCurrent(); rebuildMarkers() }
        showOverlayDialog(dlg)
    }

    /** 📍 Άγγιξε την οθόνη για να ορίσεις νέα θέση στο βήμα. */
    private fun pickLocation(i: Int) {
        addCapture("Άγγιξε το νέο σημείο για το βήμα ${i + 1}", Color.argb(50, 0, 120, 200)) { e ->
            if (e.action == MotionEvent.ACTION_UP) {
                steps.getOrNull(i)?.let { s ->
                    val ox = e.rawX - s.x; val oy = e.rawY - s.y
                    s.x += ox; s.y += oy; s.x2 += ox; s.y2 += oy    // το σύρσιμο μετακινείται ολόκληρο
                }
                removeCapture(); rebuildMarkers()
                ui.post { showStepDialog(i) }
            }
        }
        bringControlsToFront()
    }

    /** ⚙ Ρυθμίσεις: συνθήκη διακοπής κ.λπ. */
    private fun showGlobalSettings() {
        if (playing) return
        val ctx = dialogCtx()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        root.addView(TextView(ctx).apply { text = "Συνθήκη διακοπής"; textSize = 17f; setTextColor(TEAL); typeface = Typeface.DEFAULT_BOLD })

        val group = RadioGroup(ctx)
        fun radio(t: String) = RadioButton(ctx).apply {
            id = View.generateViewId(); text = t; textSize = 17f; buttonTintList = ColorStateList.valueOf(TEAL)
        }
        val rbInf = radio("Άπειρο (συνεχές, μέχρι να πατήσω ■)")
        val rbTime = radio("Χρονόμετρο")
        val rbCyc = radio("Αριθμός κύκλων")
        group.addView(rbInf)
        group.addView(rbTime)
        fun small(hint: String, v: Long) = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(v.toString()); this.hint = hint; gravity = Gravity.CENTER
            backgroundTintList = ColorStateList.valueOf(TEAL)
            layoutParams = LinearLayout.LayoutParams(dp(64), -2)
        }
        val timeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(32), 0, 0, 0) }
        val etH = small("ώρες", timeLimitSec / 3600)
        val etM = small("λεπτά", (timeLimitSec / 60) % 60)
        val etS = small("δευτ.", timeLimitSec % 60)
        timeRow.addView(etH); timeRow.addView(TextView(ctx).apply { text = " : " })
        timeRow.addView(etM); timeRow.addView(TextView(ctx).apply { text = " : " })
        timeRow.addView(etS); timeRow.addView(TextView(ctx).apply { text = "  ω : λ : δ" ; setTextColor(Color.GRAY) })
        group.addView(timeRow)
        group.addView(rbCyc)
        val cycRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(32), 0, 0, 0) }
        val etCyc = small("κύκλοι", cycles.toLong())
        cycRow.addView(etCyc); cycRow.addView(TextView(ctx).apply { text = "  Φορές"; setTextColor(Color.GRAY) })
        group.addView(cycRow)
        group.check(when (stopMode) { StopMode.INFINITE -> rbInf.id; StopMode.TIME -> rbTime.id; StopMode.CYCLES -> rbCyc.id })
        root.addView(group)

        val gapRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val etGap = fieldRow(ctx, gapRow, R.drawable.ic_timer, "Παύση ανάμεσα στους κύκλους", "χλστ. δ.")
        etGap.setText(loopGapMs.toString())
        root.addView(gapRow)

        val cbAntiAll = CheckBox(ctx).apply {
            text = "Αντί-ανίχνευση σε όλα τα βήματα"; textSize = 17f
            buttonTintList = ColorStateList.valueOf(TEAL)
            isChecked = steps.isNotEmpty() && steps.all { it.antiDetect }
            setPadding(dp(8), dp(12), 0, dp(12))
        }
        root.addView(cbAntiAll)

        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Ρυθμίσεις")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("✓ OK") { _, _ ->
                stopMode = when (group.checkedRadioButtonId) {
                    rbInf.id -> StopMode.INFINITE; rbTime.id -> StopMode.TIME; else -> StopMode.CYCLES
                }
                val h = etH.text.toString().toLongOrNull() ?: 0L
                val m = etM.text.toString().toLongOrNull() ?: 0L
                val sec = etS.text.toString().toLongOrNull() ?: 0L
                timeLimitSec = max(1L, h * 3600 + m * 60 + sec)
                cycles = etCyc.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: cycles
                loopGapMs = etGap.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: loopGapMs
                if (steps.isNotEmpty()) steps.forEach { it.antiDetect = cbAntiAll.isChecked }
                updatePill()
            }
            .setNegativeButton("Άκυρο", null)
            .create()
        showOverlayDialog(dlg)
    }

    /** 💾 «Αποθηκεύστε τις τρέχουσες ενέργειες» + «Οι διαμορφώσεις μου». */
    private fun showSaveDialog() {
        if (playing) return
        val ctx = dialogCtx()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(10)); setBackgroundColor(Color.rgb(242, 242, 242)) }
        root.addView(TextView(ctx).apply {
            text = "Αποθηκεύστε τις τρέχουσες ενέργειες"; textSize = 21f; setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(14))
        })

        // μπλε μπάρα με όνομα + δισκέτα
        val nameBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(10), dp(6)); background = rounded(BLUE, 8)
        }
        val etName = EditText(ctx).apply {
            setText("Config${ConfigStore.list(this@TapService).size + 1}")
            textSize = 22f; setTextColor(Color.WHITE); setSingleLine()
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        nameBar.addView(etName)
        val ivSave = icon(ctx, R.drawable.ic_save, 52, Color.WHITE, 6)
        nameBar.addView(ivSave)
        root.addView(nameBar)

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, dp(8)) }
        header.addView(TextView(ctx).apply {
            text = "Οι διαμορφώσεις μου"; textSize = 19f; setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        root.addView(header)

        lateinit var dlg: AlertDialog
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            list.removeAllViews()
            val items = ConfigStore.list(this)
            if (items.isEmpty()) list.addView(TextView(ctx).apply { text = "(καμία ακόμα)"; setTextColor(Color.GRAY); setPadding(dp(4), dp(8), 0, dp(8)) })
            for (c in items) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(8), dp(12))
                    background = rounded(Color.WHITE, 6); elevation = dp(2).toFloat()
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                }
                val txt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                txt.addView(TextView(ctx).apply { text = c.name; textSize = 21f; setTextColor(Color.BLACK) })
                txt.addView(TextView(ctx).apply { text = "${fmt.format(Date(c.savedAt))} · ${c.steps.size} βήματα"; textSize = 14f; setTextColor(Color.GRAY) })
                card.addView(txt)
                card.addView(icon(ctx, R.drawable.ic_play, 52, TEAL, 10).apply {
                    setOnClickListener {
                        steps.clear(); steps.addAll(c.steps.map { it.copy() }); cycles = max(1, c.loops)
                        rebuildMarkers(); dlg.dismiss(); toast("Φορτώθηκε: ${c.name}")
                    }
                })
                card.addView(icon(ctx, R.drawable.ic_delete, 52, TEAL, 10).apply {
                    setOnClickListener { ConfigStore.delete(this@TapService, c.name); fill() }
                })
                list.addView(card)
            }
        }
        fill()
        root.addView(list)
        ivSave.setOnClickListener {
            val n = etName.text.toString().trim()
            if (n.isEmpty() || steps.isEmpty()) toast("Χρειάζεται όνομα και τουλάχιστον ένα βήμα")
            else { ConfigStore.save(this, n, cycles, steps); toast("Αποθηκεύτηκε: $n"); fill() }
        }
        root.addView(TextView(ctx).apply {
            text = "Κλείσιμο"; textSize = 19f; setTextColor(TEAL); gravity = Gravity.END
            setPadding(0, dp(14), dp(4), dp(6))
            setOnClickListener { dlg.dismiss() }
        })
        dlg = AlertDialog.Builder(ctx).setView(ScrollView(ctx).apply { addView(root) }).create()
        showOverlayDialog(dlg)
    }

    private fun confirmClose() {
        val dlg = AlertDialog.Builder(dialogCtx())
            .setTitle("Κλείσιμο AutoTap;")
            .setMessage("Η πλωτή μπάρα θα κλείσει. Για να την ξαναδείς, άνοιξε την εφαρμογή AutoTap και πάτα «Ενεργοποίηση».")
            .setPositiveButton("Κλείσιμο") { _, _ -> stopPlay(); removeAll(); disableSelf() }
            .setNeutralButton("Καθαρισμός βημάτων") { _, _ -> stopPlay(); steps.clear(); rebuildMarkers() }
            .setNegativeButton("Άκυρο", null)
            .create()
        showOverlayDialog(dlg)
    }
}
