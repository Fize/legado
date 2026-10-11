package io.legado.app.lib.prefs.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.legado.app.lib.prefs.EditTextPreferenceDialog
import io.legado.app.lib.prefs.ListPreferenceDialog
import io.legado.app.lib.prefs.MultiSelectListPreferenceDialog
import io.legado.app.utils.applyNavigationBarPadding
import android.content.res.Configuration
import io.legado.app.utils.dpToPx
import io.legado.app.utils.resolveTabletContentPadding
import io.legado.app.utils.useTwoPaneLayout

abstract class PreferenceFragment : PreferenceFragmentCompat() {

    private val dialogFragmentTag = "androidx.preference.PreferenceFragment.DIALOG"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.clipToPadding = false
        listView.applyNavigationBarPadding()
        updateContentPadding()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (view != null) {
            updateContentPadding()
        }
    }

    fun updateContentPadding() {
        val rv = view?.findViewById< androidx.recyclerview.widget.RecyclerView >(androidx.preference.R.id.recycler_view)
            ?: return
        val ctx = context ?: return
        val screenWidthDp = resources.configuration.screenWidthDp
        val isDialog = parentFragment is DialogFragment
        val hPad = if (isDialog || ctx.useTwoPaneLayout) {
            0
        } else {
            resolveTabletContentPadding(
                containerWidthPx = screenWidthDp.dpToPx(),
                screenWidthDp = screenWidthDp,
                maxContentWidthPx = 680.dpToPx(),
                minTabletPaddingPx = 24.dpToPx(),
            )
        }
        if (rv.paddingLeft != hPad || rv.paddingRight != hPad) {
            rv.setPadding(hPad, rv.paddingTop, hPad, rv.paddingBottom)
            rv.adapter?.notifyDataSetChanged()
            rv.requestLayout()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onDisplayPreferenceDialog(preference: Preference) {

        var handled = false
        if (callbackFragment is OnPreferenceDisplayDialogCallback) {
            handled =
                (callbackFragment as OnPreferenceDisplayDialogCallback)
                    .onPreferenceDisplayDialog(this, preference)
        }
        if (!handled && activity is OnPreferenceDisplayDialogCallback) {
            handled = (activity as OnPreferenceDisplayDialogCallback)
                .onPreferenceDisplayDialog(this, preference)
        }

        if (handled) {
            return
        }

        // check if dialog is already showing
        if (parentFragmentManager.findFragmentByTag(dialogFragmentTag) != null) {
            return
        }

        val dialogFragment: DialogFragment = when (preference) {
            is EditTextPreference -> {
                EditTextPreferenceDialog.newInstance(preference.getKey())
            }
            is ListPreference -> {
                ListPreferenceDialog.newInstance(preference.getKey())
            }
            is MultiSelectListPreference -> {
                MultiSelectListPreferenceDialog.newInstance(preference.getKey())
            }
            else -> {
                throw IllegalArgumentException(
                    "Cannot display dialog for an unknown Preference type: "
                            + preference.javaClass.simpleName
                            + ". Make sure to implement onPreferenceDisplayDialog() to handle "
                            + "displaying a custom dialog for this Preference."
                )
            }
        }
        @Suppress("DEPRECATION")
        dialogFragment.setTargetFragment(this, 0)

        dialogFragment.show(parentFragmentManager, dialogFragmentTag)
    }

}