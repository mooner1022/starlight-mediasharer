/*
 * MediaSharerTest.js — Media Sharer 플러그인 테스트 (NeonJS)
 *
 * 쓰는 법
 * - NeonJS 프로젝트의 메인 스크립트로 쓰고, 프로젝트 설정에서 기본 메세지 수신 이벤트(onMessage)를 허용하세요.
 * - OWNER에 테스트 메시지를 보낼 사람의 이름을 넣으세요. 그 사람이 보낸 메시지에만 반응합니다.
 *   null로 두면 누구의 "!ms" 명령에나 반응하니, 여럿이 있는 방에서는 꼭 넣으세요.
 * - 채팅방에서 "!ms"를 보내면 명령 목록이 나옵니다. 각 테스트는 [PASS]/[FAIL]로 답장합니다.
 *   전송 테스트는 PASS여도 채팅방에 실제로 올라왔는지, 카카오톡에서 제때 나갔는지 눈으로 확인하세요.
 *
 * 준비물은 없습니다. 이미지는 이 파일 안의 PNG 데이터를, 로컬 파일은 모든 프로젝트 폴더에 있는 project.json을 씁니다.
 * URL을 쓰는 테스트는 인터넷 연결이 필요합니다(picsum.photos, w3.org, example.com).
 * 디버그 방으로는 보낼 수 없으므로, 디버그 방에서는 "!ms errors"와 정보 테스트(rooms, find, perm)만 의미가 있습니다.
 */

const OWNER = null;   // 예: "홍길동"

const IMAGE_URL = "https://picsum.photos/800/600";               // 확장자 없음 + 리다이렉트
const FIXED_IMAGE_URL = "https://picsum.photos/id/237/600/400";  // 항상 같은 이미지 (캐시 테스트)
const PDF_URL = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf";
const MISSING_URL = "https://example.com/404-not-found.jpg";      // 404를 돌려줌

// "G"와 "BM"으로 시작하는 텍스트: 예전 모듈은 이런 텍스트를 동영상(TS)·비트맵(BMP)으로 잘못 판별했습니다.
const SAMPLE_TEXT = "Good morning, BMW!\nMedia Sharer 텍스트 전송 테스트입니다.\n한글과 English가 섞인 UTF-8 텍스트.";

const TESTS = {
    // ---------------------------------------------------------------- 보낼 파일
    url: {
        desc: "URL 이미지 (확장자 없음, 리다이렉트)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, IMAGE_URL, { leave: "home" }), 1),
    },
    multi: {
        desc: "여러 파일 + fileName (묶음_1, 묶음_2)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, [IMAGE_URL, FIXED_IMAGE_URL], { fileName: "묶음", leave: "home" }), 2),
    },
    dup: {
        desc: "같은 URL 두 번 (한 번 받아 두 번 보냄)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, [FIXED_IMAGE_URL, FIXED_IMAGE_URL], { leave: "home" }), 2),
    },
    pdf: {
        desc: "PDF 문서 (\"테스트 문서.pdf\")",
        run: (msg) => expectSent(MediaSharer.send(msg.room, PDF_URL, { fileName: "테스트 문서", leave: "home" }), 1),
    },
    text: {
        desc: "텍스트 파일 (data URL, \"Media Sharer 텍스트.txt\")",
        run: (msg) => expectSent(MediaSharer.send(msg.room, "data:text/plain;charset=utf-8," + encodeURIComponent(SAMPLE_TEXT),
            { fileName: "Media Sharer 텍스트", leave: "home" }), 1),
    },
    datauri: {
        desc: "data URL 이미지 (주황 사각형)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, "data:image/png;base64," + PNG_ORANGE_BASE64, { leave: "home" }), 1),
    },
    base64: {
        desc: "base64: 접두사 이미지 (파란 사각형)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, "base64:" + PNG_BLUE_BASE64, { leave: "home" }), 1),
    },
    bytes: {
        desc: "Uint8Array 이미지 (초록 사각형)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, new Uint8Array(PNG_GREEN_BYTES), { leave: "home" }), 1),
    },
    buffer: {
        desc: "ArrayBuffer 이미지 (보라 사각형)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, new Uint8Array(PNG_PURPLE_BYTES).buffer, { leave: "home" }), 1),
    },
    local: {
        desc: "로컬 파일, 프로젝트 기준 상대 경로 (project.json)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, "project.json", { leave: "home" }), 1),
    },
    mixed: {
        desc: "종류가 다른 파일 묶음 (data URL + Uint8Array + URL)",
        run: (msg) => expectSent(MediaSharer.send(msg.room,
            ["data:image/png;base64," + PNG_ORANGE_BASE64, new Uint8Array(PNG_GREEN_BYTES), IMAGE_URL], { leave: "home" }), 3),
    },

    // ---------------------------------------------------------------- 보낼 방
    msg: {
        desc: "방 대신 메시지(msg)를 넘김",
        run: (msg) => expectSent(MediaSharer.send(msg, "data:image/png;base64," + PNG_ORANGE_BASE64, { leave: "home" }), 1),
    },
    name: {
        desc: "방 이름으로 보내기",
        run: (msg) => expectSent(MediaSharer.send(msg.room.name, "base64:" + PNG_BLUE_BASE64, { leave: "home" }), 1),
    },
    id: {
        desc: "채널 ID(문자열)로 보내기",
        run: (msg) => expectSent(MediaSharer.send(msg.room.id, new Uint8Array(PNG_GREEN_BYTES), { leave: "home" }), 1),
    },

    // ---------------------------------------------------------------- 카카오톡에서 나가기
    stay: {
        desc: "leave: false — 카카오톡에 머묾 (직접 나와 주세요)",
        run: (msg) => expectSent(MediaSharer.send(msg.room, "data:image/png;base64," + PNG_ORANGE_BASE64, { leave: false }), 1),
    },
    home: {
        desc: "leave: \"home\" — 파일을 읽은 직후 홈 화면으로",
        run: (msg) => expectSent(MediaSharer.send(msg.room, "base64:" + PNG_BLUE_BASE64, { leave: "home" }), 1),
    },
    starlight: {
        desc: "leave: \"starlight\" — 파일을 읽은 직후 StarLight로",
        run: (msg) => expectSent(MediaSharer.send(msg.room, new Uint8Array(PNG_GREEN_BYTES), { leave: "starlight" }), 1),
    },
    leave: {
        desc: "send() 직후 leave() — 준비 중인 전송을 기다렸다가 홈으로",
        run: async (msg) => {
            // 다운로드가 끝나기 전에 leave()를 불러도, 전송이 카카오톡을 연 뒤 파일을 읽을 때까지 기다립니다.
            const sending = MediaSharer.send(msg.room, IMAGE_URL, { leave: false });
            if (MediaSharer.leave() !== true)
                return fail("leave()가 true를 돌려주지 않음");
            return expectSent(sending, 1);
        },
    },
    delay: {
        desc: "leave(3000) — 고정 3초 뒤 홈으로",
        run: async (msg) => {
            const result = await expectSent(MediaSharer.send(msg.room, "base64:" + PNG_BLUE_BASE64, { leave: false }), 1);
            if (result.ok && MediaSharer.leave(3000) !== true)
                return fail("leave(3000)이 true를 돌려주지 않음");
            return result;
        },
    },
    nowait: {
        desc: "기다리지 않고 보내기 — 이 답장이 이미지보다 먼저 올라올 수 있음",
        run: (msg) => {
            MediaSharer.send(msg.room, "data:image/png;base64," + PNG_ORANGE_BASE64, { leave: "home" });
            return pass("send()가 바로 반환됨");
        },
    },

    // ---------------------------------------------------------------- 캐시
    cache: {
        desc: "cache: true로 같은 URL 두 번 (두 번째는 캐시에서, 더 빨라야 함)",
        run: async (msg) => {
            // 처음 상태에서 시작. (보낸 직후에 지우면 카카오톡이 아직 읽지 못한 파일이 사라지므로, 지우기는 보내기 전에만)
            const removed = MediaSharer.clearCache(FIXED_IMAGE_URL);
            const t0 = Date.now();
            const first = await MediaSharer.send(msg.room, FIXED_IMAGE_URL, { cache: true, leave: "home" });
            const t1 = Date.now();
            const second = await MediaSharer.send(msg.room, FIXED_IMAGE_URL, { cache: true, leave: "home" });
            const t2 = Date.now();
            if (!first.ok || !second.ok)
                return fail(describe(first.ok ? second : first));
            return pass("첫 번째 " + (t1 - t0) + "ms (다운로드), 두 번째 " + (t2 - t1) + "ms (캐시)"
                + (removed > 0 ? ", 시작 전 캐시 " + removed + "개 지움" : ""));
        },
    },
    clear: {
        desc: "캐시 전체 비우기 (전송 직후에는 하지 마세요)",
        run: () => pass(MediaSharer.clearCache() + "개 지움"),
    },

    // ---------------------------------------------------------------- 정보
    rooms: {
        desc: "알려진 방 목록 (최근 5개)",
        run: () => {
            const rooms = MediaSharer.getRooms();
            if (rooms.size() === 0)
                return fail("알려진 방이 없음 (카카오톡 메시지를 한 번 받은 뒤 다시 해 보세요)");
            const lines = [];
            for (let i = 0; i < Math.min(rooms.size(), 5); i++)
                lines.push(describeRoom(rooms.get(i)));
            return pass("총 " + rooms.size() + "개\n" + lines.join("\n"));
        },
    },
    find: {
        desc: "이 방 이름으로 찾기",
        run: (msg) => {
            const room = MediaSharer.findRoom(msg.room.name);
            if (room === null || room === undefined)
                return fail("찾지 못함 (디버그 방이면 정상입니다)");
            if (String(room.id) !== String(msg.room.id))
                return fail("다른 방을 찾음: " + describeRoom(room));
            return pass(describeRoom(room));
        },
    },
    perm: {
        desc: "'다른 앱 위에 표시' 권한",
        run: () => MediaSharer.hasOverlayPermission()
            ? pass("허용됨")
            : fail("허용되지 않음: StarLight가 화면에 떠 있을 때만 보낼 수 있습니다"),
    },

    // ---------------------------------------------------------------- 오류 (아무것도 보내지 않음)
    err404: {
        desc: "없는 URL (404)",
        run: (msg) => expectError(MediaSharer.send(msg.room, MISSING_URL), "HTTP 404"),
    },
    errfile: {
        desc: "없는 로컬 파일",
        run: (msg) => expectError(MediaSharer.send(msg.room, "no-such-file.png"), "File not found"),
    },
    erropt: {
        desc: "옵션 오타 (filename)",
        run: (msg) => expectError(MediaSharer.send(msg.room, IMAGE_URL, { filename: "오타" }), "Unknown option 'filename'"),
    },
    errname: {
        desc: "쓸 수 없는 파일 이름 (a/b)",
        run: (msg) => expectError(MediaSharer.send(msg.room, IMAGE_URL, { fileName: "a/b" }), "Invalid file name"),
    },
    errroom: {
        desc: "모르는 방 이름",
        run: () => expectError(MediaSharer.send("존재하지 않는 방 이름 7d1f", IMAGE_URL), "Unknown room"),
    },
    erritem: {
        desc: "배열 안의 잘못된 항목 (null)",
        run: (msg) => expectError(MediaSharer.send(msg.room, [IMAGE_URL, null]), "Item 2"),
    },

    // ---------------------------------------------------------------- 일부 실패
    partial: {
        desc: "일부만 실패 (URL + 404 URL) — 1개만 보냄",
        run: async (msg) => {
            const r = await MediaSharer.send(msg.room, [IMAGE_URL, MISSING_URL], { leave: "home" });
            if (!r.ok)
                return fail(describe(r));
            if (r.sent !== 1 || r.failed !== 1 || r.errors.size() !== 1)
                return fail("보냄 1, 빠짐 1이어야 함 — " + describe(r));
            if (!String(r.errors.get(0)).includes("404"))
                return fail("빠진 이유에 404가 없음 — " + describe(r));
            return pass(describe(r));
        },
    },
};

/** 아무것도 보내지 않고 한 번에 돌릴 수 있는 테스트 */
const ERROR_TESTS = ["err404", "errfile", "erropt", "errname", "errroom", "erritem"];

export async function onMessage(msg) {
    if (OWNER !== null && msg.sender.name !== OWNER)
        return;
    const text = String(msg.message).trim();
    if (!text.startsWith("!ms"))
        return;
    const name = text.slice(3).trim();

    if (name === "" || name === "help") {
        msg.room.send(helpText());
        return;
    }
    if (name === "errors") {
        for (const errorTest of ERROR_TESTS)
            await runTest(msg, errorTest);
        return;
    }
    if (!TESTS[name]) {
        msg.room.send("모르는 테스트: " + name + "\n\"!ms\"로 목록을 보세요.");
        return;
    }
    await runTest(msg, name);
}

async function runTest(msg, name) {
    const test = TESTS[name];
    let result;
    try {
        result = await test.run(msg);
    } catch (e) {
        result = fail("스크립트 오류: " + (e && e.message ? e.message : e));
    }
    msg.room.send("[" + (result.ok ? "PASS" : "FAIL") + "] " + name + " — " + test.desc + "\n" + result.detail);
}

/** send()의 결과가 성공이고, 보낸 파일 수가 [count]인지 */
async function expectSent(sending, count) {
    const r = await sending;
    if (!r.ok)
        return fail(describe(r));
    if (r.sent !== count)
        return fail(count + "개를 보내야 하는데 " + r.sent + "개 — " + describe(r));
    return pass(describe(r));
}

/** send()가 실패하고, 이유에 [fragment]가 들어 있는지 */
async function expectError(sending, fragment) {
    const r = await sending;
    if (r.ok)
        return fail("보내지면 안 되는데 보내짐 — " + describe(r));
    if (!String(r.error).includes(fragment))
        return fail("\"" + fragment + "\" 오류를 기대함 — " + describe(r));
    return pass(String(r.error));
}

function describe(r) {
    let text = r.ok ? "보냄 " + r.sent + "개" : "실패: " + r.error;
    if (r.failed > 0)
        text += ", 빠짐 " + r.failed + "개 (" + r.errors.get(0) + ")";
    if (r.via)
        text += ", 경로 " + r.via;
    return text;
}

function describeRoom(room) {
    const ago = Math.round((Date.now() - Number(room.lastSeen)) / 1000);
    const user = room.userId === null || room.userId === undefined ? "?" : room.userId;
    return room.name + " (" + room.id + ") " + (room.isGroupChat() ? "그룹" : "1:1") + ", 사용자 " + user + ", " + ago + "초 전";
}

function pass(detail) {
    return { ok: true, detail: detail };
}

function fail(detail) {
    return { ok: false, detail: detail };
}

function helpText() {
    const lines = ["Media Sharer 테스트 — \"!ms <이름>\"", ""];
    for (const name of Object.keys(TESTS))
        lines.push(name + ": " + TESTS[name].desc);
    lines.push("errors: 오류 테스트 " + ERROR_TESTS.length + "개를 한 번에 (아무것도 보내지 않음)");
    return lines.join("\n");
}

// 96×96 PNG (흰 테두리의 단색 사각형)
const PNG_ORANGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAAAnUlEQVR42u3dQREAQAgDsfpXgNvDAX+O7KAgAkqexoIAECBAgHYBVe4eIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAEy+u8rAiBAgAD9UAN8tgysXosn5gAAAABJRU5ErkJggg==";
const PNG_BLUE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAAAn0lEQVR42u3dQREAMAgEMazUNEYRQB3wp80OCiLgiNZYIAAECBCgVUAn69sDBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIECAAAECBAgQIEBG/31FAAQIEKAnujlYGHoi1PRrAAAAAElFTkSuQmCC";
const PNG_GREEN_BYTES = [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 96, 0, 0, 0, 96, 8, 2, 0, 0, 0, 109, 250, 224, 111, 0, 0, 0, 159, 73, 68, 65, 84, 120, 218, 237, 221, 65, 17, 0, 48, 8, 4, 49, 4, 246, 129, 83, 36, 82, 7, 252, 105, 179, 131, 130, 8, 56, 162, 53, 22, 8, 0, 1, 2, 4, 104, 21, 208, 169, 252, 246, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 144, 209, 127, 95, 17, 0, 1, 2, 4, 232, 137, 46, 87, 241, 221, 101, 134, 230, 2, 141, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130];
const PNG_PURPLE_BYTES = [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 96, 0, 0, 0, 96, 8, 2, 0, 0, 0, 109, 250, 224, 111, 0, 0, 0, 159, 73, 68, 65, 84, 120, 218, 237, 221, 65, 17, 0, 48, 8, 4, 49, 220, 212, 52, 34, 112, 72, 29, 240, 167, 205, 14, 10, 34, 224, 136, 214, 88, 32, 0, 4, 8, 16, 160, 85, 64, 121, 234, 219, 3, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 128, 0, 1, 2, 4, 8, 16, 32, 64, 70, 255, 125, 69, 0, 4, 8, 16, 160, 39, 186, 4, 39, 44, 48, 226, 226, 78, 71, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130];
