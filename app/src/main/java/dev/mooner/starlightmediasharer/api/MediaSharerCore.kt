package dev.mooner.starlightmediasharer.api

import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlightmediasharer.MediaSharerRuntime
import dev.mooner.starlightmediasharer.media.MediaSources
import dev.mooner.starlightmediasharer.room.RoomRegistry
import dev.mooner.starlightmediasharer.script.ScriptValues
import dev.mooner.starlightmediasharer.script.SendOptions
import dev.mooner.starlightmediasharer.send.KakaoShare
import dev.mooner.starlightmediasharer.send.LeaveTiming
import dev.mooner.starlightmediasharer.send.LeaveTo
import dev.mooner.starlightmediasharer.send.MediaSendService
import dev.mooner.starlightmediasharer.send.SendException
import dev.mooner.starlightmediasharer.send.SendLog
import dev.mooner.starlightmediasharer.send.SendRequest
import dev.mooner.starlightmediasharer.utils.AppContext
import dev.mooner.starlightmediasharer.utils.tr
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * What [MediaSharer] does for one project. Kept apart from it because StarLight lists every member of an API class,
 * private ones included, as the API's functions in the editor.
 */
internal class MediaSharerCore(private val project: Project) {

    @Volatile
    private var hasWarnedOverlay = false

    private val log = object : SendLog {
        override val projectName: String
            get() = project.info.name

        override fun info(message: String) =
            project.logger.info("$TAG $message")

        override fun warn(message: String) =
            project.logger.warn("$TAG $message")
    }

    fun send(room: Any?, files: Any?, options: Any?): CompletableFuture<SendResult> {
        // Script values are read here, on the script's thread; the send runs on the plugin's.
        val request = try {
            readRequest(room, files, options)
        } catch (e: Throwable) {
            return CompletableFuture.completedFuture(failure(labelOf(room), e))
        }
        val label = request.target.label
        val sending = try {
            service().send(request, log)
        } catch (e: Throwable) {
            return CompletableFuture.completedFuture(failure(label, e))
        }
        return sending.handle { outcome, error ->
            if (error != null)
                failure(label, (error as? CompletionException)?.cause ?: error)
            else
                SendResult(true, label, outcome.sent, outcome.failures.size, null, outcome.failures, outcome.via)
        }
    }

    /** [to]: `"home"` (default), `"starlight"` or a package name, or a delay alone. */
    fun leave(to: Any?, delay: Any?): Boolean {
        // leave(1000): a delay alone.
        val (to, delay) = if (ScriptValues.isAbsent(delay) && ScriptValues.unwrap(to) is Number) null to to else to to delay
        return catching(tr("Failed to leave KakaoTalk", "카카오톡에서 나가지 못했어요")) {
            val leaveTo = SendOptions.leaveToOf(to) ?: LeaveTo.Home
            // No delay: as soon as KakaoTalk has read the files of the latest send.
            val timing = ScriptValues.toLong(delay)?.takeIf { it >= 0 }?.let(LeaveTiming::After) ?: LeaveTiming.AfterRead
            service().leaveKakao(leaveTo, timing)
            true
        } ?: false
    }

    fun clearCache(target: Any?): Int =
        catching(tr("Failed to clear the cache", "캐시를 비우지 못했어요")) {
            val cache = service().cache
            if (ScriptValues.isAbsent(target))
                cache.clear()
            else
                (ScriptValues.asList(target) ?: listOf(target)).sumOf { item -> ScriptValues.toText(item)?.let(cache::clear) ?: 0 }
        } ?: 0

    fun rooms(): List<RoomInfo> =
        MediaSharerRuntime.service?.rooms?.list().orEmpty().map(::roomInfo)

    fun findRoom(name: Any?): RoomInfo? =
        ScriptValues.toText(name)?.let { MediaSharerRuntime.service?.rooms?.findByName(it) }?.let(::roomInfo)

    fun hasOverlayPermission(): Boolean =
        AppContext.getOrNull()?.let(KakaoShare::canDrawOverlays) ?: false

    private fun readRequest(room: Any?, files: Any?, options: Any?): SendRequest {
        val service = service()
        val target = service.resolver.resolve(room)
        val sources = MediaSources.parseAll(files, project.directory)
        val options = SendOptions.read(options)
        warnIfNoOverlayPermission()
        return SendRequest(target, sources, options.fileName, options.timeoutMillis, options.cache, options.headers, options.leaveTo)
    }

    private fun failure(room: String, e: Throwable): SendResult {
        val message = e.message ?: e.toString()
        project.logger.error("$TAG ${tr("Failed to send to $room", "$room 에 전송하지 못했어요")}: $message")
        return SendResult(false, room, 0, 0, message, emptyList(), null)
    }

    private fun warnIfNoOverlayPermission() {
        if (hasWarnedOverlay)
            return
        val context = AppContext.getOrNull() ?: return
        if (!KakaoShare.canDrawOverlays(context)) {
            hasWarnedOverlay = true
            log.warn(
                tr(
                    "StarLight can't draw over other apps: files are only sent while StarLight is on screen. " +
                        "Allow it in Media Sharer's settings.",
                    "'다른 앱 위에 표시' 권한이 없어 StarLight가 화면에 떠 있을 때만 파일을 보낼 수 있어요. " +
                        "Media Sharer 설정에서 권한을 허용하세요.",
                )
            )
        }
    }

    /** Runs [block], logging its error under [what]; null if it failed. */
    private inline fun <T> catching(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Throwable) {
            project.logger.error("$TAG $what: ${e.message ?: e}")
            null
        }

    /** A name for a room that couldn't be resolved. */
    private fun labelOf(room: Any?): String =
        when (val room = ScriptValues.unwrap(room)) {
            null            -> "?"
            is CharSequence -> room.toString()
            else            -> runCatching { MediaSharerRuntime.service?.resolver?.resolve(room)?.label }.getOrNull() ?: room.toString()
        }

    private fun roomInfo(room: RoomRegistry.Room): RoomInfo =
        RoomInfo(room.id, room.name, room.isGroupChat, room.lastSeen, room.userId)

    private fun service(): MediaSendService =
        MediaSharerRuntime.service ?: throw SendException("Media Sharer plugin is disabled")

    private companion object {
        const val TAG = "[MediaSharer]"
    }
}
