package com.taphive.app

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a connection owner UID to a human-readable label.
 *
 * `getNameForUid` is used instead of enumerating installed packages: it works
 * under Android 11+ package visibility rules without QUERY_ALL_PACKAGES. The
 * package name is always available as a fallback when the label is filtered.
 */
class AppNameResolver(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val cache = ConcurrentHashMap<Int, String>()

    fun name(uid: Int): String {
        cache[uid]?.let { return it }
        val resolved = when {
            uid < 0 -> "MISC"
            uid == SYSTEM_UID -> "ANDROID SYSTEM"
            uid == NETWORK_STACK_UID -> "NETWORK STACK"
            uid == SHELL_UID -> "SHELL"
            else -> {
                val packageName = try {
                    pm.getNameForUid(uid)
                } catch (_: Exception) {
                    null
                }
                val label = packageName?.let(::labelFor)
                label ?: packageName ?: "UID $uid"
            }
        }
        cache[uid] = resolved
        return resolved
    }

    private fun labelFor(packageName: String): String? = try {
        pm.getApplicationInfo(packageName, 0)
            .loadLabel(pm)
            ?.toString()
            ?.take(32)
            ?.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SYSTEM_UID = 1000
        const val NETWORK_STACK_UID = 1021
        const val SHELL_UID = 2000
    }
}
