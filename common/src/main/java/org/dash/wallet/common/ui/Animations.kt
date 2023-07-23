/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.ui

import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.view.View
import kotlin.math.exp
import kotlin.math.sin

fun View.wiggle() {
    val frequency = 3f
    val decay = 2f
    val decayingSineWave = TimeInterpolator { input ->
        val raw = sin(frequency * input * 2 * Math.PI)
        (raw * exp((-input * decay).toDouble())).toFloat()
    }

    animate()
        .xBy(-100f)
        .setInterpolator(decayingSineWave)
        .setDuration(300)
        .start()
}

val View.blinkAnimator
    get() = ObjectAnimator.ofFloat(
        this,
        View.ALPHA.name,
        0f,
        1f
    ).apply {
        duration = 500
        repeatCount = ObjectAnimator.INFINITE
        repeatMode = ObjectAnimator.REVERSE
    }
