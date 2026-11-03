package io.legado.app.ui.photo

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.PhotoRequestRepository
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.PhotoViewerRoute
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhotoEntryPoint { fun photoRequests(): PhotoRequestRepository }

fun ComponentActivity.openPhoto(url: String, sourceOrigin: String? = null, book: Book? = null) = lifecycleScope.launch {
    try {
        val repository = EntryPointAccessors.fromApplication(applicationContext, PhotoEntryPoint::class.java).photoRequests()
        val key = repository.create(url, sourceOrigin, book?.copy())
        val route = PhotoViewerRoute(key)
        if (this@openPhoto is AppActivity) navigateTo(route) else startActivity(AppActivity.startIntent(this@openPhoto, route))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { AppLog.put("打开图片失败", error); toastOnUi(R.string.photo_open_failed) }
}
