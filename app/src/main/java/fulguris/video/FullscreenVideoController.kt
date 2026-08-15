package fulguris.video

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

class FullscreenVideoController(
    private val activity: Activity
) {

    private var container: FrameLayout? = null
    private var videoContainer: FrameLayout? = null
    private var overlay: FrameLayout? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webView: WebView? = null

    private var oldOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var oldSystemUiVisibility: Int = 0
    private var oldWindowFlags: Int = 0

    private val handler = Handler(Looper.getMainLooper())

    private var controlsVisible = true
    private var isSeeking = false
    private var longPressSpeedEnabled = false

    private lateinit var topBar: LinearLayout
    private lateinit var centerPlayButton: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var hintText: TextView

    val isShowing: Boolean
        get() = container != null

    fun show(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        sourceWebView: WebView?
    ) {
        if (container != null) {
            callback.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback
        webView = sourceWebView

        saveCurrentWindowState()
        enterLandscapeFullscreen()

        val root = activity.window.decorView as ViewGroup

        val fullscreenContainer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val videoLayer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        fullscreenContainer.addView(
            videoLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val overlayLayer = createOverlay()
        fullscreenContainer.addView(
            overlayLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            fullscreenContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        container = fullscreenContainer
        videoContainer = videoLayer
        overlay = overlayLayer

        fullscreenContainer.requestFocus()

        startProgressLoop()
        showControlsTemporarily()
    }

    fun hideFromUser() {
        val callback = customViewCallback
        cleanup()
        callback?.onCustomViewHidden()
    }

    fun hideFromWebChrome() {
        cleanup()
    }

    private fun cleanup() {
        restorePlaybackRate()

        handler.removeCallbacksAndMessages(null)

        val currentContainer = container ?: return

        videoContainer?.removeAllViews()

        val parent = currentContainer.parent as? ViewGroup
        parent?.removeView(currentContainer)

        container = null
        videoContainer = null
        overlay = null
        customView = null
        customViewCallback = null
        webView = null

        restoreWindowState()
    }

    private fun createOverlay(): FrameLayout {
        val overlayLayer = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
        }

        topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(0x66000000)
        }

        val backButton = TextView(activity).apply {
            text = "‹"
            textSize = 36f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(18), dp(4))
            setOnClickListener {
                hideFromUser()
            }
        }

        val titleText = TextView(activity).apply {
            text = "视频播放"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }

        topBar.addView(
            backButton,
            LinearLayout.LayoutParams(
                dp(56),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        topBar.addView(
            titleText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        overlayLayer.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56),
                Gravity.TOP
            )
        )

        centerPlayButton = TextView(activity).apply {
            text = "▶"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundBackground(0x66000000)
            setOnClickListener {
                togglePlayPause()
                showControlsTemporarily()
            }
        }

        overlayLayer.addView(
            centerPlayButton,
            FrameLayout.LayoutParams(
                dp(88),
                dp(88),
                Gravity.CENTER
            )
        )

        bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(0x66000000)
        }

        timeText = TextView(activity).apply {
            text = "00:00 / 00:00"
            textSize = 13f
            setTextColor(Color.WHITE)
        }

        seekBar = SeekBar(activity).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) = Unit

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        isSeeking = true
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val progressValue = seekBar?.progress ?: 0
                        val ratio = progressValue / 1000.0
                        seekToRatio(ratio)
                        isSeeking = false
                        showControlsTemporarily()
                    }
                }
            )
        }

        val tipText = TextView(activity).apply {
            text = "双击左侧 -10s    双击右侧 +10s    长按 2x"
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
        }

        bottomBar.addView(
            timeText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(22)
            )
        )

        bottomBar.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        )

        bottomBar.addView(
            tipText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(20)
            )
        )

        overlayLayer.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(92),
                Gravity.BOTTOM
            )
        )

        hintText = TextView(activity).apply {
            text = ""
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = roundBackground(0x77000000)
        }

        overlayLayer.addView(
            hintText,
            FrameLayout.LayoutParams(
                dp(140),
                dp(64),
                Gravity.CENTER
            )
        )

        val gestureDetector = GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val width = overlayLayer.width
                    if (e.x < width / 2f) {
                        seekBy(-10)
                        showHint("-10s")
                    } else {
                        seekBy(10)
                        showHint("+10s")
                    }
                    showControlsTemporarily()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    longPressSpeedEnabled = true
                    setTemporaryPlaybackRate(2.0)
                    showHint("2x")
                    showControls()
                }
            }
        )

        overlayLayer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (longPressSpeedEnabled) {
                    longPressSpeedEnabled = false
                    restorePlaybackRate()
                    showHint("1x")
                    showControlsTemporarily()
                }
            }

            true
        }

        return overlayLayer
    }

    private fun saveCurrentWindowState() {
        oldOrientation = activity.requestedOrientation
        oldSystemUiVisibility = activity.window.decorView.systemUiVisibility
        oldWindowFlags = activity.window.attributes.flags
    }

    private fun enterLandscapeFullscreen() {
        activity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun restoreWindowState() {
        activity.requestedOrientation = oldOrientation

        activity.window.decorView.systemUiVisibility = oldSystemUiVisibility

        if (
            oldWindowFlags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0
        ) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControlsTemporarily()
        }
    }

    private fun showControlsTemporarily() {
        showControls()
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        centerPlayButton.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        centerPlayButton.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private fun showHint(text: String) {
        hintText.text = text
        hintText.visibility = View.VISIBLE

        handler.postDelayed(
            {
                hintText.visibility = View.GONE
            },
            700
        )
    }

    private fun togglePlayPause() {
        runVideoCommand(
            """
            if (v.paused) {
                v.play();
            } else {
                v.pause();
            }
            """.trimIndent()
        )
    }

    private fun seekBy(seconds: Int) {
        runVideoCommand(
            """
            if (isFinite(v.duration)) {
                v.currentTime = Math.min(
                    Math.max(0, v.currentTime + ($seconds)),
                    v.duration
                );
            } else {
                v.currentTime = Math.max(0, v.currentTime + ($seconds));
            }
            """.trimIndent()
        )
    }

    private fun seekToRatio(ratio: Double) {
        runVideoCommand(
            """
            if (isFinite(v.duration)) {
                v.currentTime = v.duration * $ratio;
            }
            """.trimIndent()
        )
    }

    private fun setTemporaryPlaybackRate(rate: Double) {
        runVideoCommand(
            """
            if (!window.__fulgurisOldVideoRate) {
                window.__fulgurisOldVideoRate = v.playbackRate || 1.0;
            }
            v.playbackRate = $rate;
            """.trimIndent()
        )
    }

    private fun restorePlaybackRate() {
        runVideoCommand(
            """
            if (window.__fulgurisOldVideoRate) {
                v.playbackRate = window.__fulgurisOldVideoRate;
                window.__fulgurisOldVideoRate = null;
            }
            """.trimIndent()
        )
    }

    private fun runVideoCommand(command: String) {
        val targetWebView = webView ?: return

        targetWebView.post {
            targetWebView.evaluateJavascript(
                buildVideoCommand(command),
                null
            )
        }
    }

    private fun buildVideoCommand(command: String): String {
        return """
            (function () {
                function findVideo(root) {
                    var v = root.querySelector('video');
                    if (v) return v;

                    var frames = root.querySelectorAll('iframe');
                    for (var i = 0; i < frames.length; i++) {
                        try {
                            var doc = frames[i].contentDocument;
                            if (!doc) continue;
                            v = doc.querySelector('video');
                            if (v) return v;
                        } catch (e) {}
                    }

                    return null;
                }

                var v = findVideo(document);
                if (!v) return false;

                $command

                return true;
            })();
        """.trimIndent()
    }

    private fun startProgressLoop() {
        handler.post(progressRunnable)
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 700)
        }
    }

    private fun updateProgress() {
        val targetWebView = webView ?: return

        targetWebView.evaluateJavascript(
            """
            (function () {
                function findVideo(root) {
                    var v = root.querySelector('video');
                    if (v) return v;

                    var frames = root.querySelectorAll('iframe');
                    for (var i = 0; i < frames.length; i++) {
                        try {
                            var doc = frames[i].contentDocument;
                            if (!doc) continue;
                            v = doc.querySelector('video');
                            if (v) return v;
                        } catch (e) {}
                    }

                    return null;
                }

                var v = findVideo(document);
                if (!v) return null;

                return JSON.stringify({
                    currentTime: v.currentTime || 0,
                    duration: isFinite(v.duration) ? v.duration : 0,
                    paused: v.paused,
                    rate: v.playbackRate || 1.0
                });
            })();
            """.trimIndent()
        ) { value ->
            updateUiFromVideoState(value)
        }
    }

    private fun updateUiFromVideoState(value: String?) {
        if (value.isNullOrBlank() || value == "null") {
            return
        }

        try {
            val decoded = JSONTokener(value).nextValue()
            val jsonText = decoded as? String ?: value
            val json = JSONObject(jsonText)

            val currentTime = json.optDouble("currentTime", 0.0)
            val duration = json.optDouble("duration", 0.0)
            val paused = json.optBoolean("paused", false)

            timeText.text =
                "${formatTime(currentTime)} / ${formatTime(duration)}"

            centerPlayButton.text = if (paused) "▶" else "Ⅱ"

            if (!isSeeking && duration > 0.0) {
                seekBar.progress =
                    ((currentTime / duration) * 1000.0)
                        .roundToInt()
                        .coerceIn(0, 1000)
            }
        } catch (_: Exception) {
            // Ignore malformed WebView JS result.
        }
    }

    private fun formatTime(seconds: Double): String {
        val totalSeconds = seconds.roundToInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val remainSeconds = totalSeconds % 60

        return "%02d:%02d".format(minutes, remainSeconds)
    }

    private fun roundBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(44).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density)
            .roundToInt()
    }
}