package com.vexa.meet.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
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

    fun setMirror(mirror: Boolean) {
        renderer.setMirror(mirror)
    }

    fun setScalingType(scalingType: RendererCommon.ScalingType) {
        this.scalingType = scalingType
        updateRendererAspectRatio(width, height)
    }

    fun release() {
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

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

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
