package dev.mooner.starlightmediasharer.script

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.NeonValue
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.starlightmediasharer.api.RoomInfo
import dev.mooner.starlightmediasharer.api.SendResult
import dev.mooner.starlightmediasharer.media.MediaCache
import dev.mooner.starlightmediasharer.media.MediaSource
import dev.mooner.starlightmediasharer.media.MediaSources
import dev.mooner.starlightmediasharer.room.ChatRoomRef
import dev.mooner.starlightmediasharer.room.RoomRegistry
import dev.mooner.starlightmediasharer.room.RoomResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture

/**
 * Runs `examples/neonjs/MediaSharerTest.js` in NeonJS, set up as StarLight sets it up, against a stand-in for the
 * `MediaSharer` global with the same methods. Rooms, files and options are read by the plugin's own code; only
 * downloading and handing files to KakaoTalk are simulated (URLs with "404" fail, missing local files fail).
 */
class ExampleScriptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // As StarLight's ChatRoom, Message and ChatSender look to a script.
    class Room(private val id: String, private val name: String) {
        val replies: MutableList<String> = Collections.synchronizedList(arrayListOf())
        fun getId(): String = id
        fun getName(): String = name
        fun isGroupChat(): Boolean = false
        fun isDebugRoom(): Boolean = false
        fun send(text: String): Boolean = replies.add(text)
    }

    class Sender(private val name: String) {
        fun getName(): String = name
    }

    class Message(private val message: String, private val room: Room, private val sender: Sender) {
        fun getMessage(): String = message
        fun getRoom(): Room = room
        fun getSender(): Sender = sender
    }

    /** The `MediaSharer` global's methods, over the plugin's parsing. */
    class Sharer(private val projectDir: File, private val registry: RoomRegistry) {

        private val resolver = RoomResolver(registry) { value ->
            when (value) {
                is Room    -> ChatRoomRef(value.getId(), value.getName(), value.isDebugRoom())
                is Message -> ChatRoomRef(value.getRoom().getId(), value.getRoom().getName(), false)
                else       -> null
            }
        }
        private val cached: MutableSet<String> = Collections.synchronizedSet(hashSetOf())
        val leaves: MutableList<String> = Collections.synchronizedList(arrayListOf())

        fun send(room: Any?, files: Any?): CompletableFuture<SendResult> = send(room, files, null)

        fun send(room: Any?, files: Any?, options: Any?): CompletableFuture<SendResult> {
            val result = try {
                val target = resolver.resolve(room)
                val sources = MediaSources.parseAll(files, projectDir)
                val opts = SendOptions.read(options)
                val failures = sources.mapNotNull { source ->
                    when (source) {
                        is MediaSource.Local  -> if (source.file.isFile) null else "${source.label}: File not found: ${source.file.path}"
                        is MediaSource.Remote -> if ("404" in source.url) "${source.label}: HTTP 404 Not Found: ${source.url}" else null
                        else                  -> null
                    }
                }
                if (opts.cache == true)
                    sources.filterIsInstance<MediaSource.Remote>().forEach { cached += MediaCache.keyForUrl(it.url) }
                if (failures.size == sources.size)
                    SendResult(false, target.label, 0, 0, if (sources.size == 1) failures.single().substringAfter(": ") else "None of the files could be prepared", emptyList(), null)
                else
                    SendResult(true, target.label, sources.size - failures.size, failures.size, null, failures, null)
            } catch (e: Exception) {
                SendResult(false, "?", 0, 0, e.message, emptyList(), null)
            }
            // Resolves later, as the plugin's does.
            return CompletableFuture.supplyAsync {
                Thread.sleep(10)
                result
            }
        }

        fun leave(): Boolean = leave(null, null)
        fun leave(to: Any?): Boolean = leave(to, null)
        fun leave(to: Any?, delay: Any?): Boolean = leaves.add("$to/$delay")

        fun clearCache(): Int = cached.size.also { cached.clear() }
        fun clearCache(target: Any?): Int = if (cached.remove(MediaCache.keyForUrl(target.toString()))) 1 else 0

        fun getRooms(): List<RoomInfo> =
            registry.list().map { RoomInfo(it.id, it.name, it.isGroupChat, it.lastSeen, it.userId) }

        fun findRoom(name: Any?): RoomInfo? =
            registry.findByName(name.toString())?.let { RoomInfo(it.id, it.name, it.isGroupChat, it.lastSeen, it.userId) }

        fun hasOverlayPermission(): Boolean = true
    }

    private lateinit var neon: NeonContext
    private lateinit var onMessage: NeonValue
    private lateinit var sharer: Sharer
    private val room = Room("18000000000000001", "테스트 방")
    private val owner = Sender("테스터")
    private lateinit var script: String

    @Before
    fun setUp() {
        val projectDir = tmp.newFolder("MediaSenderTest")
        projectDir.resolve("project.json").writeText("""{ "name": "MediaSenderTest" }""")
        val registry = RoomRegistry(tmp.root.resolve("rooms.json"))
        registry.recordUser(room.getId(), 95)
        registry.record(room.getId(), room.getName(), false)
        sharer = Sharer(projectDir, registry)

        neon = NeonEngine.builder()
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build())
            .hostAccess(HostAccess.builder(HostAccess.Level.ALL).defaultDenyList(false).allowLookup { true }.build())
            .webGlobals(true)
            .build()
            .newContext()
        neon["MediaSharer"] = sharer
        script = File(System.getProperty("user.dir")!!).resolve("../examples/neonjs/MediaSharerTest.js").readText()
        // As published the script answers anyone; here it is set to answer the owner only.
        assertTrue("the script ships without an owner", script.contains(NO_OWNER))
        onMessage = neon.evalModule(script.replace(NO_OWNER, "const OWNER = \"${owner.getName()}\";"), "MediaSharerTest.js")
            .getMember("onMessage")
    }

    @After
    fun tearDown() {
        neon.close()
    }

    /** Sends [text] as the owner and returns the replies it got. */
    private fun run(text: String, replies: Int = 1): List<String> {
        val before = room.replies.size
        onMessage.call(Message(text, room, owner))
        val deadline = System.currentTimeMillis() + 10_000
        while (room.replies.size < before + replies && System.currentTimeMillis() < deadline)
            neon.runEventLoop(50)
        return room.replies.drop(before)
    }

    private val tests = listOf(
        "url", "multi", "dup", "pdf", "text", "datauri", "base64", "bytes", "buffer", "local", "mixed",
        "msg", "name", "id", "stay", "home", "starlight", "leave", "delay", "nowait",
        "cache", "clear", "rooms", "find", "perm",
        "err404", "errfile", "erropt", "errname", "errroom", "erritem", "partial",
    )

    @Test
    fun everyTestPasses() {
        val failures = tests.mapNotNull { name ->
            val reply = run("!ms $name").singleOrNull() ?: return@mapNotNull "$name: no reply"
            reply.takeUnless { it.startsWith("[PASS] $name ") }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun theHelpListsEveryTest() {
        val help = run("!ms").single()
        for (name in tests + "errors")
            assertTrue("help lists $name", help.contains("\n$name: "))
    }

    @Test
    fun errorTestsRunTogether() {
        val replies = run("!ms errors", replies = 6)
        assertEquals(6, replies.size)
        assertTrue(replies.joinToString("\n"), replies.all { it.startsWith("[PASS] err") })
    }

    @Test
    fun leaveIsCalledAsTheTestsSay() {
        run("!ms leave")
        run("!ms delay")
        // A JS integer arrives as an Integer.
        assertEquals(listOf("null/null", "3000/null"), sharer.leaves)
    }

    @Test
    fun othersAndUnknownCommandsAreIgnoredOrAnswered() {
        val before = room.replies.size
        onMessage.call(Message("!ms url", room, Sender("someone else")))
        neon.runEventLoop(200)
        assertEquals(before, room.replies.size)
        assertTrue(run("!ms nope").single().startsWith("모르는 테스트: nope"))
    }

    @Test
    fun withoutAnOwnerAnyoneIsAnswered() {
        val published = neon.evalModule(script, "MediaSharerTestPublished.js").getMember("onMessage")
        val before = room.replies.size
        published.call(Message("!ms perm", room, Sender("someone else")))
        val deadline = System.currentTimeMillis() + 10_000
        while (room.replies.size == before && System.currentTimeMillis() < deadline)
            neon.runEventLoop(50)
        assertTrue(room.replies.drop(before).single().startsWith("[PASS] perm "))
    }

    private companion object {
        const val NO_OWNER = "const OWNER = null;"
    }
}
