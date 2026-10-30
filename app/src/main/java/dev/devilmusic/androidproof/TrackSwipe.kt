// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import kotlin.math.abs

/** Title-only gesture: vertical drags remain scrolling; small motion remains a tap. */
internal object TrackSwipe {
    fun direction(dx:Float,dy:Float,threshold:Float):Int = when {
        abs(dx)<threshold || abs(dx)<abs(dy)*1.5f -> 0
        dx<0 -> 1
        else -> -1
    }
}
