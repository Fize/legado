package io.legado.app.ui.test

import android.graphics.Rect

/** A floating window's own root bounds are not the visible display boundary. */
fun displayViewport(imageWidth: Int, imageHeight: Int, windowVisibleFrame: Rect): Rect {
    require(imageWidth > 0 && imageHeight > 0)
    val viewport = Rect(0, 0, imageWidth, imageHeight)
    require(viewport.intersect(windowVisibleFrame)) { "Window visible frame does not intersect screenshot" }
    require(!viewport.isEmpty) { "Window visible viewport is empty" }
    return viewport
}
