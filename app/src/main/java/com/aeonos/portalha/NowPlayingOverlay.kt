package com.aeonos.portalha

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.animation.DecelerateInterpolator
import androidx.palette.graphics.Palette
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Now-playing overlay shown while the Portal plays music as a DLNA speaker. Two looks:
 *
 *   • Default ("now playing") — dark, translucent, the live screensaver shows through.
 *     Album art bottom-left, title/artist beneath, sleek white controls on a bottom bar.
 *   • Lyrics — tap the album art to flip to a Music-Assistant-style light view: white/grey
 *     background, dark text/controls, big lyrics with the current line bold, art top-left.
 *
 * Passive display + callbacks — BridgeService owns the wiring: play/pause/stop/volume act on
 * the local renderer, prev/next go out to Music Assistant via the HA service call. Transport
 * icons are drawn as vector shapes (no colour-emoji glyphs), so they stay crisp and on-theme.
 */
class NowPlayingOverlay(
    private val context: Context,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val onPlayPause: () -> Unit,
    private val onStop: () -> Unit,
    private val onSetVolume: (Int) -> Unit,
    private val onClose: () -> Unit,
    private val positionProvider: () -> Int,
    private val durationProvider: () -> Int = { 0 },
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    @Volatile private var root: FrameLayout? = null
    private var bgLayer: View? = null
    private var content: LinearLayout? = null
    private var leftCol: LinearLayout? = null
    private var art: ImageView? = null
    private var titleView: TextView? = null
    private var albumView: TextView? = null
    private var artistView: TextView? = null
    private var playPauseIcon: MediaIcon? = null
    private var prevIcon: MediaIcon? = null
    private var nextIcon: MediaIcon? = null
    private var stopIcon: MediaIcon? = null
    private var volIcon: MediaIcon? = null
    private var volBar: SeekBar? = null
    private var lyricsClip: FrameLayout? = null
    private var lyricsView: TextView? = null
    private var progress: ProgressBar? = null
    private var progressRow: View? = null
    private var elapsedView: TextView? = null
    private var totalView: TextView? = null
    private var closeBtn: TextView? = null

    private var synced: List<Lyrics.Line>? = null
    private var plain: String? = null
    private var lastArtUri = ""
    private var highlightedLine = -1
    private var seeking = false
    private var lyricsMode = false
    private var lyricsGradient: IntArray? = null   // art-derived light gradient for the lyrics view
    private var artFromBytes = false               // artwork pushed to us, so there's no URL to reload
    private var scrollAnim: ObjectAnimator? = null

    private fun dp(v: Int) = (v * density).toInt()

    val isShowing: Boolean get() = root != null

    fun show(title: String, artist: String, album: String, artUri: String, playing: Boolean, volumePct: Int) {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            if (root == null) build()
            lastArtUri = ""            // new track → always re-fetch art, even if the URL looks unchanged
            update(title, artist, album, artUri, playing)
            volBar?.progress = volumePct
            startTicker()
        }
    }

    fun update(title: String, artist: String, album: String, artUri: String, playing: Boolean) {
        main.post {
            titleView?.text = title.ifBlank { "—" }
            albumView?.text = album
            albumView?.visibility = if (album.isBlank()) View.GONE else View.VISIBLE
            artistView?.text = artist
            playPauseIcon?.setType(if (playing) MediaIcon.PAUSE else MediaIcon.PLAY)
            if (artUri != lastArtUri) {
                lastArtUri = artUri
                loadArt(artUri)
            }
        }
    }

    fun setVolume(pct: Int) = main.post { if (!seeking) volBar?.progress = pct }

    /**
     * Artwork delivered as bytes rather than a URL — Sendspin pushes the image down the same
     * connection as the metadata, so there's nothing to fetch.
     */
    fun setArtwork(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        thread(isDaemon = true, name = "np-art-bytes") {
            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .onFailure { Log.w(TAG, "artwork decode failed", it) }.getOrNull() ?: return@thread
            val grad = runCatching {
                val p = Palette.from(bmp).generate()
                buildLightGradient(p.getLightVibrantColor(
                    p.getVibrantColor(p.getDominantColor(0xFFBFC0C8.toInt()))))
            }.getOrNull()
            main.post {
                artFromBytes = true
                art?.setImageBitmap(bmp)
                if (grad != null) {
                    lyricsGradient = grad
                    if (lyricsMode) bgLayer?.background = lightBgDrawable()
                }
            }
        }
    }

    fun setLyrics(result: Lyrics.Result?) = main.post {
        synced = result?.synced
        plain = result?.plain
        highlightedLine = -1
        renderLyrics(-1)
    }

    fun hide() = main.post {
        stopTicker()
        scrollAnim?.cancel()
        // NB: lyricsMode deliberately survives a hide. It's the view the user chose, and the
        // overlay gets torn down/rebuilt on things like a track gap — resetting it here made the
        // lyrics silently vanish back to the plain card mid-song.
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    // ── Build the view tree ─────────────────────────────────────────────────────

    private fun build() {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        // Size art off the SHORTER edge so it stays sensible in portrait as well as landscape.
        val bigArt = (minOf(screenW, screenH) * 0.40f).toInt().coerceIn(dp(150), dp(340))

        val r = FrameLayout(context)

        // Background layer — restyled per mode (translucent dark ↔ opaque light).
        bgLayer = View(context)
        r.addView(bgLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Content: art+meta column (left), lyrics (right, shown only in lyrics mode).
        content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(40), dp(48), dp(104))
        }
        leftCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(bigArt + dp(16), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        art = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(bigArt, bigArt)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF23232A.toInt()) }
            clipToOutline = true
            elevation = dp(12).toFloat()          // soft drop shadow, like the MA card
            isClickable = true
            setOnClickListener { setMode(!lyricsMode) }
        }
        titleView = TextView(context).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f); maxLines = 2
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(bigArt,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(20) }
        }
        albumView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(bigArt,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
        }
        artistView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(bigArt,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) }
        }
        leftCol!!.addView(art)
        leftCol!!.addView(titleView)
        leftCol!!.addView(albumView)
        leftCol!!.addView(artistView)
        content!!.addView(leftCol)

        // The lyric block is a plain TextView inside a clipping frame; we slide it with
        // translationY so the active line sits exactly on the vertical midpoint. (A ScrollView
        // clamps to its content bounds, so the first/last lines could never reach the centre.)
        lyricsView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(10).toFloat(), 1f)
            setPadding(dp(24), 0, dp(24), 0)
            text = ""
        }
        lyricsClip = FrameLayout(context).apply {
            visibility = View.GONE
            clipChildren = true
            addView(lyricsView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.35f)
        }
        content!!.addView(lyricsClip)

        r.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        r.addView(buildBottomBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        closeBtn = TextView(context).apply {
            text = "Close"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(22), dp(16), dp(24), dp(16))
            isClickable = true; setOnClickListener { if (lyricsMode) setMode(false) else onClose() }
        }
        r.addView(closeBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        runCatching { wm.addView(r, lp); root = r }
        applyMode()
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(6), dp(36), dp(18))
        }

        // Progress: elapsed | bar | total (hidden until a duration is known).
        val pRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        elapsedView = miniTime("0:00")
        totalView = miniTime("0:00")
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, dp(3), 1f).also {
                it.leftMargin = dp(12); it.rightMargin = dp(12) }
        }
        pRow.addView(elapsedView)
        pRow.addView(progress)
        pRow.addView(totalView)
        progressRow = pRow
        bar.addView(pRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(8) }
        }

        // Left: volume (icon + slider) — like the MA player.
        val volGroupW = dp(230)
        val volGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(volGroupW, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        volIcon = MediaIcon(context, MediaIcon.VOL).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).also { it.rightMargin = dp(6) }
        }
        volBar = SeekBar(context).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onSetVolume(p) }
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) { seeking = false }
            })
        }
        volGroup.addView(volIcon)
        volGroup.addView(volBar)
        row.addView(volGroup)

        row.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f) })

        // Centre: transport.
        prevIcon = mediaButton(MediaIcon.PREV, 46) { onPrev() }
        playPauseIcon = mediaButton(MediaIcon.PLAY, 66, primary = true) { onPlayPause() }
        nextIcon = mediaButton(MediaIcon.NEXT, 46) { onNext() }
        stopIcon = mediaButton(MediaIcon.STOP, 40) { onStop() }
        row.addView(prevIcon)
        row.addView(playPauseIcon)
        row.addView(nextIcon)
        row.addView(stopIcon)

        row.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f) })
        // Balance the centred cluster against the volume group on the left.
        row.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(volGroupW, dp(1)) })

        bar.addView(row)
        return bar
    }

    private fun mediaButton(type: Int, sizeDp: Int, primary: Boolean = false, onClick: () -> Unit): MediaIcon =
        MediaIcon(context, type, primary).apply {
            val pad = dp(10)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp) + pad * 2, dp(sizeDp) + pad * 2)
                .also { it.leftMargin = dp(6); it.rightMargin = dp(6) }
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun miniTime(t: String) = TextView(context).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    // ── Mode / theme ────────────────────────────────────────────────────────────

    private fun setMode(lyrics: Boolean) {
        if (lyricsMode == lyrics) return
        lyricsMode = lyrics
        applyMode()
        if (lyrics) { highlightedLine = -1; renderLyrics(-1) }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun applyMode() {
        val dark = !lyricsMode
        // Background.
        bgLayer?.background = if (dark)
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33000000, 0x40000000, 0x73000000))
        else
            lightBgDrawable()

        // Layout: art bottom-left (now-playing) vs top-left (lyrics); show/hide lyrics.
        leftCol?.gravity = (if (dark) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        lyricsClip?.visibility = if (dark) View.GONE else View.VISIBLE

        // Palette.
        val primaryTxt = if (dark) Color.WHITE else 0xFF17171B.toInt()
        val secondaryTxt = if (dark) 0xFFD2D4DC.toInt() else 0xFF7A7A82.toInt()
        val iconCol = if (dark) Color.WHITE else 0xFF2A2A30.toInt()
        val circleBg = if (dark) Color.WHITE else 0xFF3A3A42.toInt()
        val circleGlyph = if (dark) 0xFF15151A.toInt() else Color.WHITE
        val ctrlTint = ColorStateList.valueOf(iconCol)
        val trackTint = ColorStateList.valueOf(if (dark) 0x59FFFFFF else 0x33000000)
        val shadow = dark

        titleView?.setTextColor(primaryTxt)
        albumView?.setTextColor(secondaryTxt)
        artistView?.setTextColor(secondaryTxt)
        closeBtn?.setTextColor(if (dark) 0xFFE4E6EE.toInt() else 0xFF3A3A42.toInt())
        elapsedView?.setTextColor(secondaryTxt)
        totalView?.setTextColor(secondaryTxt)
        setShadow(shadow, titleView, albumView, artistView, closeBtn, elapsedView, totalView)

        progress?.progressTintList = ctrlTint
        progress?.progressBackgroundTintList = trackTint
        volBar?.progressTintList = ctrlTint
        volBar?.thumbTintList = ctrlTint
        volBar?.progressBackgroundTintList = trackTint

        for (mi in listOf(prevIcon, nextIcon, stopIcon, volIcon)) {
            mi?.iconColor = iconCol; mi?.shadow = shadow; mi?.invalidate()
        }
        playPauseIcon?.apply { circleColor = circleBg; glyphColor = circleGlyph; this.shadow = shadow; invalidate() }

        renderLyrics(highlightedLine)
    }

    private fun setShadow(on: Boolean, vararg views: TextView?) {
        for (v in views) {
            if (on) v?.setShadowLayer(8f, 0f, 2f, 0xB0000000.toInt())
            else v?.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    /** Soft top-to-bottom gradient for the lyrics view — tinted from the album art, kept light.
     *  Vertical (not diagonal) so each line of lyrics sits over a uniform brightness. */
    private fun lightBgDrawable(): GradientDrawable {
        val cols = lyricsGradient
            ?: intArrayOf(0xFFF6F6F8.toInt(), 0xFFECECEF.toInt(), 0xFFE2E2E6.toInt())
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols)
    }

    private fun buildLightGradient(base: Int): IntArray = intArrayOf(
        blendToWhite(base, 0.90f), blendToWhite(base, 0.80f), blendToWhite(base, 0.70f))

    private fun blendToWhite(c: Int, t: Float): Int {
        val r = (Color.red(c) * (1 - t) + 255 * t).toInt()
        val g = (Color.green(c) * (1 - t) + 255 * t).toInt()
        val b = (Color.blue(c) * (1 - t) + 255 * t).toInt()
        return Color.rgb(r, g, b)
    }

    // ── Album art ───────────────────────────────────────────────────────────────

    private fun loadArt(uri: String) {
        if (uri.isBlank()) {
            // Don't wipe artwork that was pushed to us as bytes (Sendspin) — there's no URL for it.
            if (!artFromBytes) { Log.d(TAG, "no art uri for current track"); art?.setImageDrawable(null) }
            return
        }
        artFromBytes = false
        thread(isDaemon = true, name = "np-art") {
            val bmp = runCatching {
                val conn = (URL(uri).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000; readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PortalHABridge/1.0")
                }
                conn.connect()
                val code = conn.responseCode
                val bytes = conn.inputStream.use { it.readBytes() }
                Log.d(TAG, "art fetch $uri -> HTTP $code ${bytes.size}B")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.onFailure { Log.w(TAG, "art fetch failed: $uri", it) }.getOrNull()
            if (bmp == null) { Log.w(TAG, "art decode returned null: $uri"); return@thread }
            val grad = runCatching {
                val p = Palette.from(bmp).generate()
                val base = p.getLightVibrantColor(
                    p.getVibrantColor(p.getDominantColor(0xFFBFC0C8.toInt())))
                buildLightGradient(base)
            }.getOrNull()
            main.post {
                if (lastArtUri != uri) return@post
                art?.setImageBitmap(bmp)
                if (grad != null) {
                    lyricsGradient = grad
                    if (lyricsMode) bgLayer?.background = lightBgDrawable()
                }
            }
        }
    }

    // ── Ticker: lyric highlight + progress ──────────────────────────────────────

    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            val pos = positionProvider()
            if (lyricsMode) {
                val lines = synced
                if (lines != null && lines.isNotEmpty()) {
                    var idx = -1
                    for (i in lines.indices) if (lines[i].atMs <= pos) idx = i else break
                    if (idx != highlightedLine) { highlightedLine = idx; renderLyrics(idx) }
                }
            }
            updateProgress(pos)
            main.postDelayed(this, 350)
        }
    }
    private fun startTicker() { if (!ticking) { ticking = true; main.postDelayed(ticker, 350) } }
    private fun stopTicker() { ticking = false; main.removeCallbacks(ticker) }

    private fun updateProgress(pos: Int) {
        val dur = durationProvider()
        if (dur <= 0) { progressRow?.visibility = View.GONE; return }
        progressRow?.visibility = View.VISIBLE
        val p = pos.coerceIn(0, dur)
        progress?.progress = (p.toLong() * 1000 / dur).toInt()
        elapsedView?.text = fmt(p)
        totalView?.text = fmt(dur)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun renderLyrics(current: Int) {
        val lv = lyricsView ?: return
        val lines = synced
        if (lines != null && lines.isNotEmpty()) {
            val sb = SpannableStringBuilder()
            for (i in lines.indices) {
                val start = sb.length
                sb.append(lines[i].text.ifBlank { "♪" }).append("\n\n")
                if (i == current) {
                    sb.setSpan(ForegroundColorSpan(0xFF0A0A0C.toInt()), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.12f), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    // Fade out with distance from the current line (like MA/Spotify).
                    val d = if (current < 0) 2 else kotlin.math.abs(i - current)
                    sb.setSpan(ForegroundColorSpan(lyricFade(d)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            lv.text = sb
            centerCurrentLine(current)          // current < 0 → park the first line on the midpoint
        } else {
            lv.setTextColor(if (lyricsMode) 0x8A000000.toInt() else 0x80FFFFFF.toInt())
            lv.text = if (!plain.isNullOrBlank()) plain else "No lyrics found"
        }
    }

    /**
     * Slide the whole lyric block so the active line sits on the vertical midpoint. Because we
     * move the TextView itself (translationY, which may go negative) rather than scrolling a
     * container, every line — first and last included — can reach dead centre.
     */
    private fun centerCurrentLine(current: Int) {
        val lv = lyricsView ?: return
        val clip = lyricsClip ?: return
        clip.post {
            if (clip.height == 0) return@post
            val layout = lv.layout ?: return@post
            val lineNo = minOf(current.coerceAtLeast(0) * 2, layout.lineCount - 1)
            val lineCenter =
                (layout.getLineTop(lineNo) + layout.getLineBottom(lineNo)) / 2f + lv.paddingTop
            val target = clip.height / 2f - lineCenter
            if (kotlin.math.abs(target - lv.translationY) < 1f) return@post
            scrollAnim?.cancel()
            scrollAnim = ObjectAnimator.ofFloat(lv, "translationY", lv.translationY, target).apply {
                duration = 520
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    /** Black text with distance-based alpha → lines fade in/out around the current one. */
    private fun lyricFade(d: Int): Int {
        val a = when (d) { 0, 1 -> 0x9E; 2 -> 0x70; 3 -> 0x50; 4 -> 0x3C; else -> 0x2C }
        return if (lyricsMode) (a shl 24) /* black + alpha */ else (0x80FFFFFF.toInt())
    }

    // ── Vector transport icon (no colour-emoji glyphs) ──────────────────────────

    private class MediaIcon(
        context: Context,
        private var type: Int,
        private val primary: Boolean = false,
    ) : View(context) {
        var iconColor = Color.WHITE
        var circleColor = Color.WHITE
        var glyphColor = 0xFF15151A.toInt()
        var shadow = true

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()
        private val rect = RectF()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setType(t: Int) { if (t != type) { type = t; invalidate() } }

        override fun onDraw(c: Canvas) {
            val w = (width - paddingLeft - paddingRight).toFloat()
            val h = (height - paddingTop - paddingBottom).toFloat()
            val s = minOf(w, h)
            val cx = paddingLeft + w / 2f
            val cy = paddingTop + h / 2f

            paint.clearShadowLayer()
            if (shadow) paint.setShadowLayer(s * 0.08f, 0f, s * 0.03f, 0x70000000)

            if (primary) {
                paint.color = circleColor
                c.drawCircle(cx, cy, s * 0.5f, paint)
                paint.clearShadowLayer()
                paint.color = glyphColor
                drawGlyph(c, cx, cy, s * 0.19f)
            } else {
                paint.color = iconColor
                drawGlyph(c, cx, cy, s * 0.32f)
            }
        }

        private fun drawGlyph(c: Canvas, cx: Float, cy: Float, e: Float) {
            when (type) {
                PLAY -> {
                    // Right-pointing triangle whose centroid sits exactly at (cx, cy).
                    val w = e * 1.5f
                    val baseX = cx - w / 3f
                    val tipX = cx + w * 2f / 3f
                    path.reset()
                    path.moveTo(baseX, cy - e)
                    path.lineTo(baseX, cy + e)
                    path.lineTo(tipX, cy)
                    path.close()
                    c.drawPath(path, paint)
                }
                PAUSE -> {
                    bar(c, cx - e * 0.5f, cy, e * 0.28f, e * 1.9f)
                    bar(c, cx + e * 0.5f, cy, e * 0.28f, e * 1.9f)
                }
                PREV -> {
                    triangleLeft(c, cx + e * 0.15f, cy, e)
                    bar(c, cx - e * 0.72f, cy, e * 0.26f, e * 1.7f)
                }
                NEXT -> {
                    triangleRight(c, cx - e * 0.15f, cy, e)
                    bar(c, cx + e * 0.72f, cy, e * 0.26f, e * 1.7f)
                }
                STOP -> {
                    rect.set(cx - e * 0.75f, cy - e * 0.75f, cx + e * 0.75f, cy + e * 0.75f)
                    c.drawRoundRect(rect, e * 0.22f, e * 0.22f, paint)
                }
                VOL -> {
                    // speaker body + cone
                    rect.set(cx - e * 0.9f, cy - e * 0.32f, cx - e * 0.35f, cy + e * 0.32f)
                    c.drawRect(rect, paint)
                    path.reset()
                    path.moveTo(cx - e * 0.4f, cy - e * 0.32f)
                    path.lineTo(cx + e * 0.15f, cy - e * 0.75f)
                    path.lineTo(cx + e * 0.15f, cy + e * 0.75f)
                    path.lineTo(cx - e * 0.4f, cy + e * 0.32f)
                    path.close()
                    c.drawPath(path, paint)
                    // one sound wave
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = e * 0.16f
                    c.drawArc(RectF(cx + e * 0.1f, cy - e * 0.5f, cx + e * 0.75f, cy + e * 0.5f),
                        -55f, 110f, false, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }

        private fun bar(c: Canvas, cx: Float, cy: Float, halfW: Float, fullH: Float) {
            rect.set(cx - halfW, cy - fullH / 2f, cx + halfW, cy + fullH / 2f)
            c.drawRoundRect(rect, halfW * 0.6f, halfW * 0.6f, paint)
        }

        private fun triangleRight(c: Canvas, cx: Float, cy: Float, e: Float) {
            path.reset()
            path.moveTo(cx - e * 0.7f, cy - e)
            path.lineTo(cx - e * 0.7f, cy + e)
            path.lineTo(cx + e * 0.9f, cy)
            path.close()
            c.drawPath(path, paint)
        }

        private fun triangleLeft(c: Canvas, cx: Float, cy: Float, e: Float) {
            path.reset()
            path.moveTo(cx + e * 0.7f, cy - e)
            path.lineTo(cx + e * 0.7f, cy + e)
            path.lineTo(cx - e * 0.9f, cy)
            path.close()
            c.drawPath(path, paint)
        }

        companion object {
            const val PLAY = 0; const val PAUSE = 1; const val PREV = 2
            const val NEXT = 3; const val STOP = 4; const val VOL = 5
        }
    }

    companion object { private const val TAG = "NPOverlay" }
}
