package com.vexa.meet.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.ViewOutlineProvider
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class RoundedTextureViewRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener, VideoSink {

    private val renderer = EglRenderer("RoundedTextureViewRenderer")
    private var drawer: GlRectDrawer? = null
    private var isInitialized = false
    private var isSurfaceReady = false
    private var cornerRadiusPx = 0f
    private var scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL

    init {
        isOpaque = false
        surfaceTextureListener = this
        clipToOutline = true
    }

    fun init(eglContext: EglBase.Context, radiusPx: Float) {
        if (isInitialized) return
        cornerRadiusPx = radiusPx
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        drawer = GlRectDrawer()
        renderer.init(eglContext, EglBase.CONFIG_PLAIN, drawer)
        isInitialized = true
        updateRendererAspectRatio(width, height)
        surfaceTexture?.let {
            if (!isSurfaceReady) {
                renderer.createEglSurface(it)
                isSurfaceReady = true
            }
        }
    }

    private var held = false
    private var lastPreview: Bitmap? = null
    private var lastPreviewTime = 0L
    private var pauseView: View? = null
    private var pauseParent: ViewGroup? = null
    private val pauseLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        pauseView?.layout(left, top, right, bottom)
    }

    fun setOnHold(paused: Boolean) {
        if (held == paused) return
        held = paused
        if (paused) attachPauseView() else removePauseView()
    }

    private fun attachPauseView() {
        if (!held || pauseView != null) return
        val container = parent as? ViewGroup ?: return
        val frozenPreview = lastPreview
        val view = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            override fun onDraw(canvas: Canvas) {
                // Enlarge the saved low-resolution frame for a soft blur. Never paint
                // a black fallback: if no frame exists yet, leave the video visible.
                frozenPreview?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), paint) }
                paint.color = android.graphics.Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = minOf(18f * resources.displayMetrics.scaledDensity, width / 8f)
                paint.setShadowLayer(3f, 0f, 1f, android.graphics.Color.DKGRAY)
                canvas.drawText(context.getString(com.vexa.meet.R.string.video_paused), width / 2f,
                    height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
                paint.clearShadowLayer()
            }
        }.apply {
            contentDescription = context.getString(com.vexa.meet.R.string.video_paused)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            outlineProvider = this@RoundedTextureViewRenderer.outlineProvider
            clipToOutline = true
        }
        pauseView = view
        pauseParent = container
        container.addView(view, container.indexOfChild(this) + 1, ViewGroup.LayoutParams(0, 0))
        container.addOnLayoutChangeListener(pauseLayoutListener)
        addOnLayoutChangeListener(pauseLayoutListener)
        view.layout(left, top, right, bottom)
    }

    private fun removePauseView() {
        pauseParent?.removeOnLayoutChangeListener(pauseLayoutListener)
        removeOnLayoutChangeListener(pauseLayoutListener)
        pauseView?.let { pauseParent?.removeView(it) }
        pauseView = null
        pauseParent = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachPauseView()
    }

    override fun onDetachedFromWindow() {
        removePauseView()
        super.onDetachedFromWindow()
    }

    fun setMirror(mirror: Boolean) {
        renderer.setMirror(mirror)
    }

    fun setScalingType(scalingType: RendererCommon.ScalingType) {
        this.scalingType = scalingType
        updateRendererAspectRatio(width, height)
    }

    fun release() {
        setOnHold(false)
        lastPreview = null
        renderer.release()
        drawer?.release()
        drawer = null
        isInitialized = false
        isSurfaceReady = false
    }

    override fun onFrame(frame: VideoFrame) {
        renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        updateRendererAspectRatio(width, height)
        if (isInitialized && !isSurfaceReady) {
            renderer.createEglSurface(surface)
            isSurfaceReady = true
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateRendererAspectRatio(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        isSurfaceReady = false
        renderer.releaseEglSurface { }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val now = android.os.SystemClock.uptimeMillis()
        if (held || width <= 0 || height <= 0 || now - lastPreviewTime < 250) return
        lastPreviewTime = now
        // Cache while video is live, before another app takes the camera.
        runCatching { getBitmap(12, maxOf(1, (12f * height / width).toInt())) }
            .getOrNull()?.let { lastPreview = it }
    }

    private fun updateRendererAspectRatio(width: Int, height: Int) {
        if (!isInitialized || width <= 0 || height <= 0) return
        val viewAspect = width.toFloat() / height.toFloat()
        renderer.setLayoutAspectRatio(
            when (scalingType) {
                RendererCommon.ScalingType.SCALE_ASPECT_FIT -> 0f
                else -> viewAspect
            }
        )
    }
}
