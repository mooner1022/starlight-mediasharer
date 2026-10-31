package dev.mooner.starlightmediasharer.room

import dev.mooner.starlight.plugincore.chat.ChatRoom
import dev.mooner.starlight.plugincore.chat.Message

/** The chat room of StarLight's `msg.room` or `msg`. */
fun starLightChatRoomOf(value: Any): ChatRoomRef? {
    val room = when (value) {
        is ChatRoom -> value
        is Message  -> value.room
        else        -> return null
    }
    return ChatRoomRef(room.id, room.name, room.isDebugRoom)
}
