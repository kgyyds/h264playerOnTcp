package com.kgapp.h264opusServer.control

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

data class DeviceFrameSize(val width: Int, val height: Int)

data class DevicePoint(val x: Int, val y: Int)

object ControlType {
    const val INJECT_KEYCODE = 0
    const val INJECT_TOUCH_EVENT = 2
}

object MotionAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
}

object KeyAction {
    const val DOWN = 0
    const val UP = 1
}

object AndroidKeyCode {
    const val POWER = 26
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val HOME = 3
    const val BACK = 4
    const val APP_SWITCH = 187
}

private object Be {
    fun u8(v: Int) = byteArrayOf((v and 0xFF).toByte())

    fun i16(v: Int): ByteArray = ByteBuffer
        .allocate(2)
        .order(ByteOrder.BIG_ENDIAN)
        .putShort(v.toShort())
        .array()

    fun i32(v: Int): ByteArray = ByteBuffer
        .allocate(4)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(v)
        .array()

    fun i64(v: Long): ByteArray = ByteBuffer
        .allocate(8)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(v)
        .array()
}

private fun u16FixedPoint(f: Float): Int {
    val clamped = f.coerceIn(0f, 1f)
    return (clamped * 0xFFFF).toInt().coerceIn(0, 0xFFFF)
}

fun encodeInjectTouch(
    action: Int,
    pointerId: Long,
    p: DevicePoint,
    frame: DeviceFrameSize,
    pressure: Float,
    actionButton: Int = 0,
    buttons: Int = 0,
): ByteArray {
    val out = ByteArrayOutputStream(32)
    out.write(Be.u8(ControlType.INJECT_TOUCH_EVENT))
    out.write(Be.u8(action))
    out.write(Be.i64(pointerId))
    out.write(Be.i32(p.x))
    out.write(Be.i32(p.y))
    out.write(Be.i16(frame.width))
    out.write(Be.i16(frame.height))
    out.write(Be.i16(u16FixedPoint(pressure)))
    out.write(Be.i32(actionButton))
    out.write(Be.i32(buttons))
    return out.toByteArray()
}

fun encodeInjectKeycode(
    action: Int,
    keycode: Int,
    repeat: Int = 0,
    metastate: Int = 0,
): ByteArray {
    val out = ByteArrayOutputStream(14)
    out.write(Be.u8(ControlType.INJECT_KEYCODE))
    out.write(Be.u8(action))
    out.write(Be.i32(keycode))
    out.write(Be.i32(repeat))
    out.write(Be.i32(metastate))
    return out.toByteArray()
}

fun mapViewToVideo(
    vx: Float,
    vy: Float,
    viewW: Int,
    viewH: Int,
    videoW: Int,
    videoH: Int,
): DevicePoint? {
    if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return null
    val scale = min(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
    val contentW = videoW * scale
    val contentH = videoH * scale
    val offsetX = (viewW - contentW) / 2f
    val offsetY = (viewH - contentH) / 2f

    val cx = vx - offsetX
    val cy = vy - offsetY
    if (cx < 0 || cy < 0 || cx >= contentW || cy >= contentH) return null

    val x = (cx / scale).toInt().coerceIn(0, videoW - 1)
    val y = (cy / scale).toInt().coerceIn(0, videoH - 1)
    return DevicePoint(x, y)
}
