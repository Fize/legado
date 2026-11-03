package io.legado.app.lib.cronet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only restores this probe's saved Cronet switch after an interrupted native process. */
@RunWith(AndroidJUnit4::class)
class CronetNativePreferenceRepair {
    @Test fun restoreOwnedPreference() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cronetCompatibilityRepair") == "yes")
        val id = requireNotNull(args.getString("cronetCompatibilityId"))
        require(Regex("[a-f0-9-]{36}").matches(id))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "cronet-native-compat/$id")
        val original = JSONObject(File(directory, "original-pref.json").readText())
        check(original.getString("id") == id)
        restoreCronetPreference(context.defaultSharedPreferences, original)
        File(directory, "preference-restored.json").writeText(JSONObject().put("id", id)
            .put("restored", true).put("hostRepair", true).toString())
    }
}
