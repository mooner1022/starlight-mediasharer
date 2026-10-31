package dev.mooner.starlightmediasharer.utils

import android.annotation.SuppressLint
import android.content.Context

/**
 * The application context of StarLight.
 *
 * PluginCore hands plugins no Android context, so it is looked up through the framework once and, failing that, taken
 * from the first event or settings screen that carries one.
 */
object AppContext {

    @Volatile
    private var cached: Context? = null

    /** Remembers [context] if none is known yet. */
    fun offer(context: Context?) {
        if (cached == null && context != null)
            cached = context.applicationContext ?: context
    }

    fun getOrNull(): Context? =
        cached ?: lookup()?.also { cached = it }

    fun get(): Context =
        getOrNull() ?: throw IllegalStateException(
            tr("Android context is not available yet", "Android 컨텍스트를 아직 가져오지 못했어요")
        )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun lookup(): Context? =
        invokeStatic("android.app.ActivityThread", "currentApplication")
            ?: invokeStatic("android.app.AppGlobals", "getInitialApplication")

    private fun invokeStatic(className: String, method: String): Context? =
        try {
            Class.forName(className).getMethod(method).invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
}
