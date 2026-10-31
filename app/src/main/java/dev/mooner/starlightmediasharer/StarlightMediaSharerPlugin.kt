package dev.mooner.starlightmediasharer

import dev.mooner.configdsl.ConfigStructure
import dev.mooner.configdsl.Icon
import dev.mooner.configdsl.config
import dev.mooner.configdsl.options.InfoState
import dev.mooner.configdsl.options.OptionStatus
import dev.mooner.configdsl.options.button
import dev.mooner.configdsl.options.choice
import dev.mooner.configdsl.options.info
import dev.mooner.configdsl.options.seekbar
import dev.mooner.configdsl.options.spinner
import dev.mooner.configdsl.options.textList
import dev.mooner.configdsl.options.toggle
import dev.mooner.configdsl.utils.PeekType
import dev.mooner.starlight.plugincore.config.data.ConfigData
import dev.mooner.starlight.plugincore.event.EventHandler
import dev.mooner.starlight.plugincore.event.Events
import dev.mooner.starlight.plugincore.event.on
import dev.mooner.starlight.plugincore.logger.LoggerFactory
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin
import dev.mooner.starlightmediasharer.api.MediaSharerApi
import dev.mooner.starlightmediasharer.send.KakaoShare
import dev.mooner.starlightmediasharer.send.LeaveTo
import dev.mooner.starlightmediasharer.send.MediaSendService
import dev.mooner.starlightmediasharer.send.SendHistory
import dev.mooner.starlightmediasharer.send.SendSettings
import dev.mooner.starlightmediasharer.utils.AppContext
import dev.mooner.starlightmediasharer.utils.tr
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG = LoggerFactory.logger("MediaSharer")

@Suppress("unused")
class StarlightMediaSharerPlugin : StarlightPlugin() {

    private val settingsFlow = MutableStateFlow(SendSettings())
    private val settings: SendSettings
        get() = settingsFlow.value

    private var pluginScope: CoroutineScope? = null

    override fun onEnable() {
        updateSettings(getPluginConfig())
        // An exception escaping a coroutine would otherwise reach the thread's handler and crash StarLight.
        val errorHandler = CoroutineExceptionHandler { _, e ->
            LOG.error { tr("Unexpected error: $e", "예상치 못한 오류: $e") }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)
        pluginScope = scope

        val service = MediaSendService(getInternalDataDirectory(), { settings }, scope)
        // Before the service is handed out: it moves the last run's leftovers aside (small, local file work).
        service.start()
        MediaSharerRuntime.service = service

        addApi(MediaSharerApi())

        EventHandler.on<Events.Message.Create>(scope) {
            val room = message.room
            if (message.packageName == KakaoShare.KAKAO_PACKAGE && !room.isDebugRoom)
                service.rooms.record(room.id, room.name, room.isGroupChat)
        }
        EventHandler.on<Events.Notification.Post>(scope) {
            // Only needed if the framework lookup fails: notifications carry StarLight's context.
            AppContext.offer(context)
            // Which KakaoTalk a room belongs to (the owner's, or a clone such as Dual Messenger): its notification's
            // user. StarLight fires this before parsing the message.
            val sbn = statusBarNotification
            val tag = sbn.tag
            if (sbn.packageName == KakaoShare.KAKAO_PACKAGE && tag != null)
                @Suppress("DEPRECATION")
                service.rooms.recordUser(tag, sbn.userId)
        }

        val context = AppContext.getOrNull()
        when {
            context == null ->
                LOG.warn { tr("Android context is not available yet", "Android 컨텍스트를 아직 가져오지 못했어요") }
            !KakaoShare.canDrawOverlays(context) ->
                LOG.warn {
                    tr(
                        "StarLight can't draw over other apps: files are only sent while StarLight is on screen.",
                        "'다른 앱 위에 표시' 권한이 없어 StarLight가 화면에 떠 있을 때만 파일을 보낼 수 있어요.",
                    )
                }
        }
    }

    override fun onDisable() {
        val service = MediaSharerRuntime.service
        MediaSharerRuntime.service = null
        pluginScope?.cancel()
        pluginScope = null
        // Keep the rooms seen since the last save; queued deletions run again on the next start.
        if (service != null)
            CoroutineScope(Dispatchers.IO).launch { runCatching { service.stop() } }
    }

    override fun onConfigUpdated(config: ConfigData, updated: Map<String, Set<String>>) {
        super.onConfigUpdated(config, updated)
        updateSettings(config)
    }

    private fun updateSettings(config: ConfigData) {
        val send = config.category(CATEGORY_SEND)
        val cache = config.category(CATEGORY_CACHE)
        settingsFlow.value = SendSettings(
            downloadTimeoutMillis = send.getInt(CONF_TIMEOUT, DEF_TIMEOUT_SECONDS) * 1000,
            maxFileBytes = FILE_LIMITS.getOrElse(send.getInt(CONF_MAX_FILE, DEF_FILE_LIMIT)) { FILE_LIMITS[DEF_FILE_LIMIT] },
            tempRetentionMillis = send.getInt(CONF_RETENTION, DEF_RETENTION_SECONDS) * 1000L,
            sendIntervalMillis = send.getInt(CONF_INTERVAL, 0) * 100L,
            leaveTo = LeaveTo.ofSetting(send.getString(CONF_AFTER_SEND)),
            leaveMaxWaitMillis = send.getInt(CONF_RETURN_DELAY, DEF_RETURN_DELAY_SECONDS) * 1000L,
            leaveGraceMillis = send.getInt(CONF_LEAVE_GRACE, DEF_LEAVE_GRACE_TENTHS) * 100L,
            logSends = send.getBoolean(CONF_LOG_SENDS, false),
            cacheByDefault = cache.getBoolean(CONF_CACHE_DEFAULT, false),
            cacheMaxItems = cache.getInt(CONF_CACHE_ITEMS, DEF_CACHE_ITEMS),
            cacheMaxBytes = CACHE_LIMITS.getOrElse(cache.getInt(CONF_CACHE_SIZE, DEF_CACHE_LIMIT)) { CACHE_LIMITS[DEF_CACHE_LIMIT] },
        )
    }

    // ------------------------------------------------------------------ config

    override fun getConfigStructure(): ConfigStructure = config {
        category {
            id = CATEGORY_STATUS
            title = tr("Status", "상태")
            textColor = color { COLOR_TITLE }
            items {
                info {
                    id = "kakao_status"
                    title = tr("KakaoTalk", "카카오톡")
                    icon = Icon.SEND
                    bind(ticker { kakaoState() })
                }
                info {
                    id = "overlay_status"
                    title = tr("Draw over other apps", "다른 앱 위에 표시")
                    description = tr(
                        "Needed to open KakaoTalk while StarLight is in the background. Tap to open the setting.",
                        "StarLight가 백그라운드에 있을 때 카카오톡을 열려면 필요해요. 눌러서 설정을 열어요.",
                    )
                    icon = Icon.LAYERS
                    bind(ticker { overlayState() })
                    setOnClickListener { view ->
                        AppContext.offer(view.context)
                        runCatching { KakaoShare.openOverlaySettings(view.context) }
                    }
                }
                info {
                    id = "other_users_status"
                    title = tr("Dual Messenger KakaoTalk", "듀얼 메신저 카카오톡")
                    description = tr(
                        "Sending to rooms of the KakaoTalk in another user (Dual Messenger) without Android asking " +
                            "needs StarLight to declare and be granted ${KakaoShare.INTERACT_ACROSS_USERS}:\n" +
                            "adb shell pm grant ${STARLIGHT_PACKAGE} ${KakaoShare.INTERACT_ACROSS_USERS}",
                        "다른 사용자(듀얼 메신저)의 카카오톡 방으로 선택창 없이 보내려면 StarLight가 " +
                            "${KakaoShare.INTERACT_ACROSS_USERS} 권한을 선언하고 부여받아야 해요:\n" +
                            "adb shell pm grant ${STARLIGHT_PACKAGE} ${KakaoShare.INTERACT_ACROSS_USERS}",
                    )
                    icon = Icon.ACCOUNT_BOX
                    bind(ticker { otherUsersState() })
                }
            }
        }
        category {
            id = CATEGORY_SEND
            title = tr("Sending", "전송")
            textColor = color { COLOR_SUB_TITLE }
            items {
                seekbar {
                    id = CONF_TIMEOUT
                    title = tr("Download timeout (seconds)", "다운로드 제한 시간 (초)")
                    description = tr(
                        "For connecting and each wait for data. Scripts can override it with the timeout option.",
                        "연결과 데이터 대기마다 적용돼요. 스크립트의 timeout 옵션이 우선해요.",
                    )
                    min = 5
                    max = 120
                    defaultValue = DEF_TIMEOUT_SECONDS
                    icon = Icon.TIMER
                    iconTintColor = color { COLOR_ICON }
                }
                spinner {
                    id = CONF_MAX_FILE
                    title = tr("Max file size", "최대 파일 크기")
                    items = FILE_LIMIT_LABELS
                    defaultIndex = DEF_FILE_LIMIT
                    icon = Icon.FILE
                    iconTintColor = color { COLOR_ICON }
                }
                seekbar {
                    id = CONF_INTERVAL
                    title = tr("Interval between sends (×0.1 s)", "연속 전송 간격 (×0.1초)")
                    description = tr(
                        "Raise it if KakaoTalk drops files sent right after one another.",
                        "연달아 보낸 파일이 누락되면 늘려 보세요.",
                    )
                    min = 0
                    max = 50
                    defaultValue = 0
                    icon = Icon.ACCESS_TIME
                    iconTintColor = color { COLOR_ICON }
                }
                seekbar {
                    id = CONF_RETENTION
                    title = tr("Keep temporary files (seconds)", "임시 파일 보관 시간 (초)")
                    description = tr(
                        "Downloads and copies are deleted this long after sending. Raise it for large videos.",
                        "다운로드하거나 복사한 파일은 전송 후 이 시간이 지나면 삭제돼요. 큰 동영상은 늘려 주세요.",
                    )
                    min = 10
                    max = 600
                    defaultValue = DEF_RETENTION_SECONDS
                    icon = Icon.DELETE_SWEEP
                    iconTintColor = color { COLOR_ICON }
                }
                choice {
                    id = CONF_AFTER_SEND
                    title = tr("After sending", "전송 후")
                    description = tr(
                        "Unless a script's leave option says otherwise. While a chat room is open, KakaoTalk posts " +
                            "no notifications, so a bot should leave.",
                        "스크립트의 leave 옵션이 없을 때 적용돼요. 채팅방이 열려 있는 동안에는 알림이 오지 않으니 봇이라면 나가는 것이 좋아요.",
                    )
                    option(LeaveTo.KEY_STAY, tr("Stay in KakaoTalk", "카카오톡에 머물기"))
                    option(LeaveTo.KEY_HOME, tr("Go to the home screen", "홈 화면으로 이동"))
                    option(LeaveTo.KEY_STARLIGHT, tr("Go back to StarLight", "StarLight로 돌아가기"))
                    defaultKey = LeaveTo.KEY_STAY
                    icon = Icon.EXIT_TO_APP
                    iconTintColor = color { COLOR_ICON }
                }
                seekbar {
                    id = CONF_RETURN_DELAY
                    title = tr("Longest stay in KakaoTalk (seconds)", "카카오톡 최대 체류 시간 (초)")
                    description = tr(
                        "KakaoTalk is left as soon as it has read the files; if that isn't seen, after this. " +
                            "Also used by MediaSharer.leave() without a delay.",
                        "카카오톡이 파일을 읽으면 바로 나가고, 읽는 것이 감지되지 않으면 이 시간 뒤에 나가요. " +
                            "지연 없이 호출한 MediaSharer.leave()에도 적용돼요.",
                    )
                    min = 1
                    max = 30
                    defaultValue = DEF_RETURN_DELAY_SECONDS
                    icon = Icon.TIMER
                    iconTintColor = color { COLOR_ICON }
                }
                seekbar {
                    id = CONF_LEAVE_GRACE
                    title = tr("Wait after KakaoTalk read the files (×0.1 s)", "파일을 읽은 뒤 대기 (×0.1초)")
                    description = tr(
                        "Time left to KakaoTalk to post the message. While a chat room is open, KakaoTalk posts no " +
                            "notifications, so keep it short; raise it if files stop being sent.",
                        "카카오톡이 메시지를 올릴 시간이에요. 채팅방이 열려 있는 동안에는 알림이 오지 않으니 짧게 두고, " +
                            "전송이 누락되면 늘려 주세요.",
                    )
                    min = 0
                    max = 30
                    defaultValue = DEF_LEAVE_GRACE_TENTHS
                    icon = Icon.ACCESS_TIME
                    iconTintColor = color { COLOR_ICON }
                }
                toggle {
                    id = CONF_LOG_SENDS
                    title = tr("Log sends", "전송 로그 남기기")
                    description = tr("Logs every send in the project's log.", "모든 전송을 프로젝트 로그에 남겨요.")
                    defaultValue = false
                    icon = Icon.LIST_BULLETED
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_CACHE
            title = tr("Cache", "캐시")
            textColor = color { COLOR_SUB_TITLE }
            items {
                toggle {
                    id = CONF_CACHE_DEFAULT
                    title = tr("Cache by default", "기본으로 캐시 사용")
                    description = tr(
                        "Keeps downloaded and decoded files to send them again without downloading. Scripts can override it with the cache option.",
                        "다운로드·디코딩한 파일을 보관해 다시 보낼 때 내려받지 않아요. 스크립트의 cache 옵션이 우선해요.",
                    )
                    defaultValue = false
                    icon = Icon.CLOUD_DOWNLOAD
                    iconTintColor = color { COLOR_ICON }
                }
                seekbar {
                    id = CONF_CACHE_ITEMS
                    title = tr("Max cached files", "최대 캐시 파일 수")
                    min = 10
                    max = 1000
                    defaultValue = DEF_CACHE_ITEMS
                    icon = Icon.LAYERS
                    iconTintColor = color { COLOR_ICON }
                }
                spinner {
                    id = CONF_CACHE_SIZE
                    title = tr("Max cache size", "최대 캐시 크기")
                    items = CACHE_LIMIT_LABELS
                    defaultIndex = DEF_CACHE_LIMIT
                    icon = Icon.ARCHIVE
                    iconTintColor = color { COLOR_ICON }
                }
                button {
                    id = "clear_cache"
                    title = tr("Clear cache", "캐시 비우기")
                    icon = Icon.DELETE
                    bindDescription(ticker { cacheUsage() })
                    action {
                        val service = MediaSharerRuntime.service ?: return@action
                        if (!confirm(tr("Clear cache", "캐시 비우기"), tr("Delete every cached file?", "캐시된 파일을 모두 삭제할까요?")))
                            return@action
                        val count = withContext(Dispatchers.IO) { service.cache.clear() }
                        peek(tr("Deleted $count files", "파일 ${count}개를 삭제했어요"), PeekType.SUCCESS)
                    }
                }
            }
        }
        category {
            id = CATEGORY_ROOMS
            title = tr("Chat rooms", "채팅방")
            textColor = color { COLOR_SUB_TITLE }
            items {
                textList {
                    id = "known_rooms"
                    title = tr("Known rooms", "알려진 채팅방")
                    description = tr(
                        "Rooms messages came from, which scripts can name instead of a channel ID.",
                        "메시지를 받은 채팅방이에요. 스크립트에서 채널 ID 대신 방 이름으로 지정할 수 있어요.",
                    )
                    icon = Icon.LIST_BULLETED
                    copyable = true
                    emptyText = tr("No messages received yet", "아직 받은 메시지가 없어요")
                    lines = knownRooms()
                    bind(serviceFlow { service -> service.rooms.version.map { knownRooms() } })
                }
                button {
                    id = "forget_rooms"
                    title = tr("Forget rooms", "채팅방 목록 지우기")
                    icon = Icon.DELETE_SWEEP
                    action {
                        val service = MediaSharerRuntime.service ?: return@action
                        if (!confirm(tr("Forget rooms", "채팅방 목록 지우기"), tr("Forget every known room?", "알려진 채팅방을 모두 지울까요?")))
                            return@action
                        withContext(Dispatchers.IO) { service.rooms.clear() }
                        peek(tr("Forgot every room", "채팅방 목록을 지웠어요"), PeekType.SUCCESS)
                    }
                }
            }
        }
        category {
            id = CATEGORY_HISTORY
            title = tr("History", "기록")
            textColor = color { COLOR_SUB_TITLE }
            items {
                textList {
                    id = "recent_sends"
                    title = tr("Recent sends", "최근 전송")
                    icon = Icon.SEND
                    monospace = true
                    copyable = true
                    emptyText = tr("Nothing sent yet", "아직 보낸 파일이 없어요")
                    lines = recentSends()
                    bind(serviceFlow { service -> service.history.version.map { recentSends() } })
                }
            }
        }
    }

    private fun kakaoState(): InfoState {
        val context = AppContext.getOrNull()
            ?: return InfoState(tr("No context", "컨텍스트 없음"), OptionStatus.ERROR)
        return if (KakaoShare.isKakaoInstalled(context))
            InfoState(tr("Installed", "설치됨"), OptionStatus.OK)
        else
            InfoState(tr("Not installed", "설치되지 않음"), OptionStatus.ERROR)
    }

    private fun overlayState(): InfoState {
        val context = AppContext.getOrNull()
            ?: return InfoState(null, OptionStatus.LOADING)
        return if (KakaoShare.canDrawOverlays(context))
            InfoState(tr("Allowed", "허용됨"), OptionStatus.OK)
        else
            InfoState(tr("Not allowed", "허용 필요"), OptionStatus.WARNING)
    }

    /** Granted, or a warning if a known room belongs to another user's KakaoTalk. */
    private fun otherUsersState(): InfoState {
        val context = AppContext.getOrNull()
            ?: return InfoState(null, OptionStatus.LOADING)
        if (KakaoShare.canReachOtherUsers(context))
            return InfoState(tr("Granted", "허용됨"), OptionStatus.OK)
        val myUserId = KakaoShare.myUserId()
        val hasOtherUserRooms = MediaSharerRuntime.service?.rooms?.list().orEmpty()
            .any { it.userId != null && it.userId != myUserId }
        return if (hasOtherUserRooms)
            InfoState(tr("Not granted: Android asks", "권한 없음: 선택창 표시"), OptionStatus.WARNING)
        else
            InfoState(tr("Not granted", "권한 없음"))
    }

    private fun cacheUsage(): String {
        val usage = MediaSharerRuntime.service?.cache?.usage()
            ?: return tr("Plugin is disabled", "플러그인이 꺼져 있어요")
        return tr("${usage.items} files, ${formatBytes(usage.bytes)}", "파일 ${usage.items}개, ${formatBytes(usage.bytes)}")
    }

    /**
     * The flow [block] makes of the service, looked up when the settings screen collects it rather than when the
     * structure is built, which may happen before the plugin is enabled.
     */
    private fun <T> serviceFlow(block: (MediaSendService) -> Flow<T>): Flow<T> =
        flow {
            MediaSharerRuntime.service?.let { emitAll(block(it)) }
        }

    /** Re-reads [block] every few seconds while the settings screen shows it. */
    private fun <T> ticker(block: () -> T): Flow<T> =
        flow {
            while (true) {
                emit(block())
                delay(TICKER_MILLIS)
            }
        }.flowOn(Dispatchers.IO)

    private companion object {
        const val CATEGORY_STATUS  = "status"
        const val CATEGORY_SEND    = "send"
        const val CATEGORY_CACHE   = "cache"
        const val CATEGORY_ROOMS   = "rooms"
        const val CATEGORY_HISTORY = "history"

        const val CONF_TIMEOUT       = "download_timeout"
        const val CONF_MAX_FILE      = "max_file_size"
        const val CONF_INTERVAL      = "send_interval"
        const val CONF_RETENTION     = "temp_retention"
        const val CONF_AFTER_SEND    = "after_send"
        const val CONF_RETURN_DELAY  = "return_delay"
        const val CONF_LEAVE_GRACE   = "leave_grace"
        const val CONF_LOG_SENDS     = "log_sends"
        const val CONF_CACHE_DEFAULT = "cache_by_default"
        const val CONF_CACHE_ITEMS   = "cache_max_items"
        const val CONF_CACHE_SIZE    = "cache_max_size"

        const val DEF_TIMEOUT_SECONDS = 30
        const val DEF_RETENTION_SECONDS = 60
        const val DEF_RETURN_DELAY_SECONDS = 5
        const val DEF_LEAVE_GRACE_TENTHS = 5
        const val DEF_CACHE_ITEMS = 200

        val FILE_LIMITS = longArrayOf(10L shl 20, 50L shl 20, 100L shl 20, 300L shl 20, 1L shl 30)
        val FILE_LIMIT_LABELS = listOf("10MB", "50MB", "100MB", "300MB", "1GB")
        const val DEF_FILE_LIMIT = 3

        val CACHE_LIMITS = longArrayOf(50L shl 20, 100L shl 20, 250L shl 20, 500L shl 20, 1L shl 30, 2L shl 30)
        val CACHE_LIMIT_LABELS = listOf("50MB", "100MB", "250MB", "500MB", "1GB", "2GB")
        const val DEF_CACHE_LIMIT = 3

        const val TICKER_MILLIS = 3_000L

        const val COLOR_TITLE     = "#FFB86C"
        const val COLOR_SUB_TITLE = "#E09B55"
        const val COLOR_ICON      = "#FFB86C"

        const val STARLIGHT_PACKAGE = "dev.mooner.starlight"

        fun knownRooms(): List<String> {
            val myUserId = KakaoShare.myUserId()
            return MediaSharerRuntime.service?.rooms?.list().orEmpty().map { room ->
                "${room.name} — ${room.id}" +
                    (if (room.isGroupChat) "" else " (1:1)") +
                    (room.userId?.takeIf { it != myUserId }?.let { tr(" [user $it]", " [사용자 $it]") } ?: "")
            }
        }

        fun recentSends(): List<String> =
            MediaSharerRuntime.service?.history?.snapshot().orEmpty().map(::describe)

        fun describe(record: SendHistory.Record): String {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.time))
            val result = record.error?.let { "✗ $it" }
                ?: ("✓ ${record.files}" + if (record.failed > 0) " (!${record.failed})" else "")
            val via = record.via?.let { " [$it]" } ?: ""
            return "$time ${record.room}$via $result ${record.durationMillis}ms"
        }

        fun formatBytes(bytes: Long): String =
            when {
                bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1fGB", bytes / (1L shl 30).toDouble())
                bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1fMB", bytes / (1L shl 20).toDouble())
                bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1fKB", bytes / (1L shl 10).toDouble())
                else               -> "${bytes}B"
            }
    }
}
