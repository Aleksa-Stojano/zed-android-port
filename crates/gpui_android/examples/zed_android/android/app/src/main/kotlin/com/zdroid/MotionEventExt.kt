package com.zdroid

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface

/// Sum all historical + current samples of a relative MotionEvent axis.
/// `AXIS_RELATIVE_X` / `AXIS_RELATIVE_Y` are NOT accumulated across
/// batched samples by the framework; `getAxisValue` returns only the
/// most recent. Without summing the historical samples fast finger
/// motion loses ~80% of its travel (Tab S9 Ultra trackpad batches
/// ~6-10 samples per event).
internal fun sumRelativeAxis(event: MotionEvent, axis: Int, pointerIndex: Int): Float {
    var sum = 0f
    val historySize = event.historySize
    for (h in 0 until historySize) {
        sum += event.getHistoricalAxisValue(axis, pointerIndex, h)
    }
    sum += event.getAxisValue(axis, pointerIndex)
    return sum
}

/// Mouse pointer-acceleration curve: base sensitivity multiplier plus a
/// gentle quadratic boost capped well below the touch trackpad's 4x.
/// Under pointer capture Android bypasses its own acceleration and hands
/// us raw device counts, so without this the cursor crawls on a high-res
/// panel. Shared by MainActivity (primary window) and ExtraWindowActivity
/// (settings / spawned windows) so the cursor feels identical in both.
///   |d|=1  -> ~1.6   (precision when slow)
///   |d|=10 -> ~22
///   |d|=30 -> ~96    (boost capped at 2x)
internal fun accelerateMouse(delta: Float): Float {
    val magnitude = kotlin.math.abs(delta)
    val direction = kotlin.math.sign(delta)
    val boost = kotlin.math.min(1f + magnitude * magnitude * MOUSE_ACCEL_COEF, MOUSE_ACCEL_CAP)
    return direction * magnitude * MOUSE_SENSITIVITY * boost
}

private const val MOUSE_SENSITIVITY = 1.6f
private const val MOUSE_ACCEL_COEF = 0.004f
private const val MOUSE_ACCEL_CAP = 2.0f

private const val POINTER_TAG = "ZdroidPointer"

/// Display rotation in Surface.ROTATION_* constants.
internal fun Activity.currentDisplayRotation(): Int {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }
    return display?.rotation ?: Surface.ROTATION_0
}

/// Bluetooth mice already emit screen-space deltas. Trackpads (including
/// Samsung Book Cover, which often reports SOURCE_MOUSE + TOOL_TYPE_FINGER)
/// deliver device-axis counts under pointer capture and need a rotation remap.
internal fun shouldRemapRelativeAxes(event: MotionEvent): Boolean {
    if (event.source and InputDevice.SOURCE_TOUCHPAD != 0) {
        return true
    }
    if (event.pointerCount == 0) {
        return false
    }
    val tool = event.getToolType(0)
    return tool == MotionEvent.TOOL_TYPE_FINGER
}

/// Map device-axis relative deltas into screen space after display rotation.
/// Screen coords: +X right, +Y down. ROTATION_90 is 90° clockwise:
/// right→down, down→left, left→up, up→right — the Tab S8 Ultra Book Cover
/// landscape mismatch.
internal fun remapCapturedRelativeDelta(
    event: MotionEvent,
    rx: Float,
    ry: Float,
    rotation: Int,
): Pair<Float, Float> {
    if (!shouldRemapRelativeAxes(event)) {
        return rx to ry
    }
    val mapped = when (rotation) {
        Surface.ROTATION_0 -> rx to ry
        Surface.ROTATION_90 -> ry to -rx
        Surface.ROTATION_180 -> -rx to -ry
        Surface.ROTATION_270 -> -ry to rx
        else -> rx to ry
    }
    if (Log.isLoggable(POINTER_TAG, Log.DEBUG) && (rx != 0f || ry != 0f)) {
        val tool = if (event.pointerCount > 0) event.getToolType(0) else -1
        Log.d(
            POINTER_TAG,
            "rot=$rotation src=0x${Integer.toHexString(event.source)} tool=$tool " +
                "raw=($rx,$ry) mapped=(${mapped.first},${mapped.second})",
        )
    }
    return mapped
}
