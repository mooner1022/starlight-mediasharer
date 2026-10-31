package dev.mooner.starlightmediasharer.api

/**
 * What `MediaSharer.send` resolves to, whether or not it succeeded: `r.ok`, `r.error`, ...
 *
 * A failed send resolves too (never rejects), so a send nobody awaits doesn't end in an unhandled rejection; its
 * reason is in the project's log either way.
 */
class SendResult internal constructor(
    /** Whether KakaoTalk got the files (some of them, if [failed] > 0). */
    val ok: Boolean,
    /** The room, as `name (channel ID)`. */
    val room: String,
    /** Files handed to KakaoTalk. */
    val sent: Int,
    /** Files left out because they couldn't be prepared; see [errors]. */
    val failed: Int,
    /** Why nothing was sent; null if KakaoTalk got the files. */
    val error: String?,
    /** Why each file left out failed, as `source: reason`. */
    val errors: List<String>,
    /** How KakaoTalk was reached if not directly: `"user 95"` (Dual Messenger) or `"chooser"`; else null. */
    val via: String?,
) {

    override fun toString(): String =
        if (ok) "SendResult(ok, room=$room, sent=$sent, failed=$failed${via?.let { ", via=$it" } ?: ""})"
        else "SendResult(failed, room=$room, error=$error)"
}

/** A KakaoTalk chat room a message came from. */
class RoomInfo internal constructor(
    /** The channel ID. */
    val id: String,
    val name: String,
    val isGroupChat: Boolean,
    /** When a message from it last came, in epoch milliseconds. */
    val lastSeen: Long,
    /** The Android user whose KakaoTalk has it: 0 for the owner, another for Dual Messenger; null if unknown. */
    val userId: Int?,
) {

    override fun toString(): String =
        "RoomInfo($name, id=$id${userId?.let { ", user=$it" } ?: ""})"
}
