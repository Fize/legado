@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import splitties.init.appCtx
import java.io.File

/**
 * 获取自定义路径的SharedPreferences, 用反射生成 SharedPreferences
 * @param dir 目录路径
 * @param fileName 文件名,不需要 '.xml' 后缀
 * @return SharedPreferences
 */
@SuppressLint("DiscouragedPrivateApi")
fun Context.getSharedPreferences(
    dir: String,
    fileName: String
): SharedPreferences? {
    try {
        val fieldMBase = ContextWrapper::class.java.getDeclaredField("mBase")
        fieldMBase.isAccessible = true
        var preferencesContext = this
        while (preferencesContext is ContextWrapper) {
            val base = fieldMBase.get(preferencesContext) as? Context ?: break
            if (base === preferencesContext) break
            preferencesContext = base
        }
        val fieldMPreferencesDir = preferencesContext.javaClass.getDeclaredField("mPreferencesDir")
        fieldMPreferencesDir.isAccessible = true
        val file = File(dir)
        return synchronized(preferencesContext.javaClass) {
            val originalPreferencesDir = fieldMPreferencesDir.get(preferencesContext)
            fieldMPreferencesDir.set(preferencesContext, file)
            try {
                preferencesContext.getSharedPreferences(fileName, Activity.MODE_PRIVATE)
            } finally {
                fieldMPreferencesDir.set(preferencesContext, originalPreferencesDir)
            }
        }
    } catch (e: NoSuchFieldException) {
        e.printOnDebug()
    } catch (e: IllegalArgumentException) {
        e.printOnDebug()
    } catch (e: IllegalAccessException) {
        e.printOnDebug()
    }
    return null
}

fun SharedPreferences.getString(key: String): String? {
    return getString(key, null)
}

fun SharedPreferences.putString(key: String, value: String) {
    edit {
        putString(key, value)
    }
}

fun SharedPreferences.getBoolean(key: String): Boolean {
    return getBooleanCompat(key, false)
}

fun SharedPreferences.getBooleanCompat(key: String, defValue: Boolean): Boolean {
    return try {
        getBoolean(key, defValue)
    } catch (_: ClassCastException) {
        val value = parseBooleanPreference(all[key], defValue)
        edit { putBoolean(key, value) }
        value
    }
}

internal fun parseBooleanPreference(value: Any?, defValue: Boolean): Boolean =
    (value as? String)?.trim()?.toBooleanStrictOrNull() ?: defValue

fun SharedPreferences.putBoolean(key: String, value: Boolean) {
    edit {
        putBoolean(key, value)
    }
}

fun SharedPreferences.getInt(key: String): Int {
    return getInt(key, 0)
}

fun SharedPreferences.putInt(key: String, value: Int) {
    edit {
        putInt(key, value)
    }
}

fun SharedPreferences.getLong(key: String): Long {
    return getLong(key, 0)
}

fun SharedPreferences.putLong(key: String, value: Long) {
    edit {
        putLong(key, value)
    }
}

fun SharedPreferences.getFloat(key: String): Float {
    return getFloat(key, 0f)
}

fun SharedPreferences.putFloat(key: String, value: Float) {
    edit {
        putFloat(key, value)
    }
}

fun SharedPreferences.remove(key: String) {
    edit {
        remove(key)
    }
}

fun LifecycleOwner.observeSharedPreferences(
    prefs: SharedPreferences = appCtx.defaultSharedPreferences,
    l: SharedPreferences.OnSharedPreferenceChangeListener
) {
    val observer = object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
            prefs.registerOnSharedPreferenceChangeListener(l)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
            lifecycle.removeObserver(this)
        }

        override fun onPause(owner: LifecycleOwner) {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
        }

        override fun onResume(owner: LifecycleOwner) {
            prefs.registerOnSharedPreferenceChangeListener(l)
        }
    }
    lifecycle.addObserver(observer)
}
