@file:Suppress("unused")

package io.legado.app.help.http

import android.text.TextUtils
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.equalsRegex
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager.getCookieNoSession
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.help.http.api.CookieManagerInterface
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.removeCookie
import io.legado.app.utils.splitNotBlank

/** The WebView cookie store could not be opened, so login data was not cleared. */
class WebCookieUnavailableException(cause: RuntimeException) : Exception(cause)

@Keep
object CookieStore : CookieManagerInterface {

    /**
     *保存cookie到数据库，会自动识别url的二级域名
     */
    override fun setCookie(url: String, cookie: String?) {
        try {
            saveCookie(url, cookie)
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$e", e)
        }
    }

    private fun saveCookie(url: String, cookie: String?, publishMemory: Boolean = true): Cookie {
        val domain = NetworkUtils.getSubDomain(url)
        val cookieValue = cookie ?: ""
        val saved = Cookie(domain, cookieValue)
        appDb.cookieDao.insert(saved)
        if (publishMemory) CacheManager.putMemory("${domain}_cookie", cookieValue)
        return saved
    }

    fun setWebCookie(url: String, cookie: String) {
        try {
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            val cookies = cookie.splitNotBlank(";")
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeSessionCookies(null)
            cookies.forEach {
                cookieManager.setCookie(baseUrl, it)
            }
        } catch (e: Exception) {
            AppLog.put("设置WebCookie失败\n$e", e)
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(cookie)) {
            return
        }
        setCookie(url, mergeCookie(url, cookie))
    }

    internal fun restoreCookie(url: String, cookie: String, publishMemory: Boolean = true): Cookie? {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(cookie)) {
            return null
        }
        val merged = if (publishMemory) {
            mergeCookie(url, cookie)
        } else {
            // A restore transaction may already have imported another URL of this domain.
            // Read its uncommitted Room value rather than the intentionally unchanged cache.
            val domain = NetworkUtils.getSubDomain(url)
            mergeCookieValues(appDb.cookieDao.get(domain)?.cookie.orEmpty(), cookie)
        }
        return saveCookie(url, merged, publishMemory)
    }

    private fun mergeCookie(url: String, cookie: String): String {
        return mergeCookieValues(getCookieNoSession(url), cookie)
    }

    internal fun mergeCookieValues(oldCookie: String, cookie: String): String {
        if (oldCookie.isEmpty()) {
            return cookie
        }
        val cookieMap = cookieToMap(oldCookie)
        cookieMap.putAll(cookieToMap(cookie))
        return mapToCookie(cookieMap).orEmpty()
    }

    /**
     *获取url所属的二级域名的cookie
     */
    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)

        val cookie = getCookieNoSession(url)
        val sessionCookie = CookieManager.getSessionCookie(domain)

        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)

        var ck = mapToCookie(cookieMap) ?: ""
        while (ck.length > 4096) {
            val removeKey = cookieMap.keys.random()
            CookieManager.removeCookie(url, removeKey)
            cookieMap.remove(removeKey)
            ck = mapToCookie(cookieMap) ?: ""
        }
        return ck
    }

    fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        val sessionCookie = CookieManager.getSessionCookie(url)
        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)
        return cookieMap[key] ?: ""
    }

    override fun removeCookie(url: String) {
        // Open the WebView store before modifying local cookies. Otherwise a missing
        // provider leaves a partially cleared login and may expose old web cookies later.
        val webCookies = try {
            android.webkit.CookieManager.getInstance()
        } catch (error: RuntimeException) {
            throw WebCookieUnavailableException(error)
        }
        webCookies.removeCookie(url)
        val domain = NetworkUtils.getSubDomain(url)
        appDb.cookieDao.delete(domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
    }

    override fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank()) {
            return cookieMap
        }
        val pairArray = cookie.split(semicolonRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pair in pairArray) {
            val pairs = pair.split(equalsRegex, 2).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size <= 1) {
                continue
            }
            val key = pairs[0].trim { it <= ' ' }
            val value = pairs[1]
            if (value.isNotBlank() || value.trim { it <= ' ' } == "null") {
                cookieMap[key] = value.trim { it <= ' ' }
            }
        }
        return cookieMap
    }

    override fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) {
            return null
        }
        val builder = StringBuilder()
        cookieMap.keys.forEachIndexed { index, key ->
            if (index > 0) builder.append("; ")
            builder.append(key).append("=").append(cookieMap[key])
        }
        return builder.toString()
    }

    fun clear() {
        appDb.cookieDao.deleteOkHttp()
    }

}
