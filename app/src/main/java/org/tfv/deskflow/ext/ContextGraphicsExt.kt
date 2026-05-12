/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.tfv.deskflow.ext

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import org.tfv.deskflow.client.models.Size
import org.tfv.deskflow.client.models.SizeF

data class ScreenSize(val px: Size, val dp: SizeF, val scale: Float)

/**
 * Get the screen size for a specific display.
 *
 * @param displayId The ID of the display to get metrics for. If null, uses the context's display.
 * @return ScreenSize containing pixel dimensions, dp dimensions, and scale factor.
 */
fun Context.getScreenSize(displayId: Int? = null): ScreenSize {
    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    
    val display = if (displayId != null) {
        displayManager.getDisplay(displayId) ?: run {
            try { this.display } catch (e: Exception) { null } ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        }
    } else {
        try { this.display } catch (e: Exception) { null } ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    }

    val displayContext = createDisplayContext(display!!)
    val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = wm.maximumWindowMetrics
    val bounds = metrics.bounds

    val widthPx = bounds.width()
    val heightPx = bounds.height()
    val density = displayContext.resources.displayMetrics.density
    val widthDp = widthPx / density
    val heightDp = heightPx / density

    return ScreenSize(Size(widthPx, heightPx), SizeF(widthDp, heightDp), density)
}
