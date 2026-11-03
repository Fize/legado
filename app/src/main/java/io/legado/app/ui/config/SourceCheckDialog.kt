package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.SourceTargetStateStore
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

/** The system restores arguments and the draft; actions always use the current host. */
class SourceCheckDialog : BaseComposeDialogFragment(adaptationSoftKeyboard = true) {
    private val store by lazy { targetStore() }
    private var lease: SourceTargetStateStore.Lease? = null
    private var targetsUnavailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val args = requireArguments()
            check(!args.getBoolean(UNAVAILABLE))
            var reference = args.getString(SNAPSHOT)
            if (reference == null) {
                // Upgrade an old saved task before its next state is sent back to Android.
                check(args.containsKey(TARGETS))
                val owner = store.create(requireActivity().taskId).also { lease = it }
                reference = store.save(owner, listOf(args.getStringArrayList(TARGETS).orEmpty()))
                args.putString(SNAPSHOT, reference)
            } else lease = store.claim(reference)
            check(store.read(reference).size == 1)
        } catch (error: Exception) {
            targetsUnavailable = true
            arguments?.apply {
                remove(SNAPSHOT)
                putBoolean(UNAVAILABLE, true)
            }
            releaseTargets()
            lease = null
            AppLog.put("恢复校验目标失败", error)
        } finally {
            // Failed legacy conversion must not send the original large list back to Android.
            arguments?.remove(TARGETS)
        }
    }

    private fun releaseTargets() {
        lease?.let { owner -> runCatching { store.release(owner) }.onFailure { AppLog.put("清理校验目标快照失败", it) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRemoving || activity?.isFinishing == true) releaseTargets()
    }

    @Composable
    override fun Content() {
        LegadoTextInputDialog(
            title = stringResource(R.string.search_book_key),
            initialValue = requireArguments().getString(KEYWORD).orEmpty(),
            hint = "search word",
            actionsBelowEditor = true,
            message = if (targetsUnavailable) "无法恢复校验目标，请关闭后重新选择书源" else null,
            neutralText = stringResource(R.string.check_source_config),
            onNeutral = { (requireActivity() as BookSourceActivity).showDialogFragment<CheckSourceConfig>() },
            onDismiss = { releaseTargets(); dismissAllowingStateLoss() },
            onConfirm = { keyword ->
                runCatching {
                    check(!targetsUnavailable)
                    val lists = store.read(requireArguments().getString(SNAPSHOT)!!)
                    check(lists.size == 1)
                    (requireActivity() as BookSourceActivity).confirmSourceCheck(keyword, lists.single())
                }.onFailure {
                    AppLog.put("读取校验目标失败", it)
                    requireContext().toastOnUi("无法恢复校验目标，请重新选择书源")
                }
            },
        )
    }

    companion object {
        private const val KEYWORD = "keyword"
        private const val TARGETS = "targets"
        private const val SNAPSHOT = "targetSnapshot"
        private const val UNAVAILABLE = "targetsUnavailable"
        private fun targetStore() = SourceTargetStateStore(File(appCtx.noBackupFilesDir, "source-target-state"))
        fun create(keyword: String, targets: Collection<String>, taskId: Int): SourceCheckDialog {
            val store = targetStore()
            val owner = store.create(taskId)
            try {
                val reference = store.save(owner, listOf(targets.toList()))
                return SourceCheckDialog().apply {
                    arguments = Bundle().apply {
                        putString(KEYWORD, keyword)
                        putString(SNAPSHOT, reference)
                    }
                }
            } catch (error: Exception) {
                runCatching { store.release(owner) }
                throw error
            }
        }
    }
}
