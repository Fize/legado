package io.legado.app.ui.book.manga.recyclerview

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MangaLayoutManager(private val context: Context) :
    LinearLayoutManager(context) {

    override fun calculateExtraLayoutSpace(
        state: RecyclerView.State,
        extraLayoutSpace: IntArray,
    ) {
        val dm = context.resources.displayMetrics
        val space = if (orientation == HORIZONTAL) {
            maxOf(width, dm.widthPixels)
        } else {
            maxOf(height * 3 / 4, dm.heightPixels * 3 / 4)
        }
        extraLayoutSpace[0] = space
        extraLayoutSpace[1] = space
    }

}
