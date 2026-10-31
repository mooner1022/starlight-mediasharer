package dev.mooner.starlightmediasharer

import dev.mooner.starlightmediasharer.send.MediaSendService

/** What the plugin shares with the projects' `MediaSharer` objects while it is enabled. */
object MediaSharerRuntime {

    @Volatile
    var service: MediaSendService? = null
        internal set
}
