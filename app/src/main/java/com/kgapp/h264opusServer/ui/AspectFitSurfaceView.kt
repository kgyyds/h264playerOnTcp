package com.kgapp.h264opusServer.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

class AspectFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    private var videoWidth: Int = 16
    private var videoHeight: Int = 9

    fun setVideoAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (videoWidth != width || videoHeight != height) {
            videoWidth = width
            videoHeight = height
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0 || parentWidth == 0 || parentHeight == 0) {
            setMeasuredDimension(parentWidth, parentHeight)
            return
        }

        val aspect = videoWidth.toFloat() / videoHeight.toFloat()
        var measuredWidth = parentWidth
        var measuredHeight = (measuredWidth / aspect).roundToInt()

        if (measuredHeight > parentHeight) {
            measuredHeight = parentHeight
            measuredWidth = (measuredHeight * aspect).roundToInt()
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }
}
