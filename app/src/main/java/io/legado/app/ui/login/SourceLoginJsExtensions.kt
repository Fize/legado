package io.legado.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.JsExtensions
import io.legado.app.model.ReadAloud
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import java.io.File
import java.lang.ref.WeakReference

@Suppress("unused")
class SourceLoginJsExtensions(
    activity: AppCompatActivity?,
    source: BaseSource?,
    private val bookType: Int = 0,
    callback: Callback? = null
) : JsExtensions {
    private val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)
    private val sourceRef: WeakReference<BaseSource> = WeakReference(source)
    
    internal var customButtonKey: String? = null
    internal var uiScope: CoroutineScope? = null
    internal var uiOwnerKey: String? = null
    private val callbackRef: WeakReference<Callback> = WeakReference(callback)
    interface Callback {
        fun upUiData(data: Map<String, Any?>?)
        fun reUiView(deltaUp: Boolean = false)
    }

    override fun getSource(): BaseSource? {
        return sourceRef.get()
    }

    override fun getTag(): String? = getSource()?.getTag()

    fun upLoginData(data: Map<String, Any?>?) {
        callbackRef.get()?.upUiData(data)
    }

    @JvmOverloads
    fun reLoginView(deltaUp: Boolean = false) {
        callbackRef.get()?.reUiView(deltaUp)
    }

    fun refreshExplore() {
        callbackRef.get()?.reUiView()
    }

    fun copyText(text: String) {
        if (uiScope?.isActive == false) return
        activityRef.get()?.sendToClip(text)
    }

    fun clearTtsCache() {
        if (getSource() !is HttpTTS) return
        val activity = activityRef.get() ?: return
        (uiScope ?: activity.lifecycleScope).launch(IO) {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${activity.cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            activity.toastOnUi(R.string.clear_cache_success)
        }
    }

    override fun showBrowser(
        url: String,
        html: String?,
        preloadJs: String?,
        config: String?
    ) {
        val activity = activityRef.get() ?: return
        val source = getSource() ?: return
        val show = {
            activity.showDialogFragment(
                BottomWebViewDialog(source.getKey(), bookType, url, html, preloadJs, config).apply {
                    customButtonKey = this@SourceLoginJsExtensions.customButtonKey
                    ownerEntryKey = uiOwnerKey
                }
            )
        }
        val owner = uiScope
        if (owner == null) show()
        else owner.launch(Dispatchers.Main.immediate) { if (isActive) show() }
    }

}
