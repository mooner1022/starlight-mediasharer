# StarLight Media Sharer

StarLight 프로젝트(스크립트)에서 카카오톡 채팅방으로 이미지·동영상·파일을 보내는 플러그인입니다.
메신저봇용 [MediaSender 모듈](mediaSender.js)(Hehee, v1.5.1)을 StarLight 플러그인으로 다시 구현하고 개선했습니다.

**StarLight nightly(PluginCore 1.0.0) 이상이 필요합니다.** NeonJS에서 쓰려면 플러그인 Api를 NeonJS에 노출하는 빌드가 필요합니다
(2026-10-31 기준 nightly 소스에서 `Java`를 제외한 모든 Api가 노출되는 것을 확인). Rhino에서는 그대로 사용할 수 있습니다.

## 스크립트에서 사용하기

플러그인을 켜면 모든 프로젝트에 전역 객체 `MediaSharer`가 생깁니다(플러그인 이름과 같고, 원래 모듈을 `MediaSender`로
불러오던 스크립트와 겹치지 않습니다). 플러그인을 켜기 전에 컴파일된 프로젝트는 다시 컴파일해야 보입니다.

`send()`는 **바로 반환**하고 결과는 나중에 알려 줍니다. 다운로드하는 동안 스크립트를 막지 않으므로, 프로젝트가 그사이 온
메시지도 계속 처리합니다. NeonJS에서는 Promise라 `await`할 수 있고, Rhino에서는 `CompletableFuture`라 `.join()`으로 기다릴 수
있습니다. 결과가 필요 없으면 그냥 호출만 하면 됩니다(실패 이유는 프로젝트 로그에 남습니다).

```js
// NeonJS
export async function onMessage(msg) {
    if (msg.message === "!고양이")
        MediaSharer.send(msg, "https://example.com/cat.jpg", { leave: "home" });   // 보내고 끝

    if (msg.message === "!사진들") {
        const r = await MediaSharer.send(msg.room, ["images/a.png", "images/b.png"], { fileName: "앨범" });
        if (!r.ok)
            msg.room.send("전송 실패: " + r.error);
    }
}
```

```js
// Rhino
function onMessage(msg) {
    if (msg.message == "!로고") {
        var r = MediaSharer.send(msg.room, "/sdcard/StarLight/logo.png").join();   // 기다리기
        if (!r.ok) msg.room.send("전송 실패: " + r.error);
    }
}

// 레거시 response 이벤트: room은 방 이름 문자열
function response(room, msg, sender, isGroupChat, replier) {
    if (msg == "!로고") MediaSharer.send(room, "/sdcard/StarLight/logo.png");
}
```

### 보낼 방

| 값 | 설명 |
|---|---|
| `msg`, `msg.room` | `onMessage`로 받은 메시지(또는 그 채팅방). 가장 확실한 방법입니다. |
| `"18478608147447180"`, `18478608147447180n` | 카카오톡 채널 ID (문자열 또는 BigInt). JS 숫자는 2^53을 넘으면 자릿수가 깨지므로 거부합니다. |
| `"방 이름"` | 메시지를 받은 적 있는 방의 이름. 같은 이름이 여럿이면 가장 최근 방이 선택됩니다. |

방 이름과 채널 ID 목록은 플러그인 설정의 **채팅방**에서 볼 수 있고(눌러서 복사), `MediaSharer.getRooms()`로도 얻을 수 있습니다.
디버그 방으로는 보낼 수 없습니다.

### 보낼 파일

| 값 | 설명 |
|---|---|
| 경로 | `/sdcard/...`, `sdcard/...`, `file://...`, 또는 **프로젝트 디렉터리 기준 상대 경로** |
| URL | `http://`, `https://` (리다이렉트를 따라가며 http→https 전환도 처리) |
| data URL | `data:image/png;base64,...`, `data:text/plain,...` |
| Base64 | `base64:...`, `base64,...`, 또는 접두사 없는 Base64 문자열(64자 이상) |
| 바이트 | Java `byte[]`, NeonJS의 `Uint8Array`·`ArrayBuffer`·`DataView` (원소 단위로 복사하므로 64MB까지) |
| 비트맵 | `android.graphics.Bitmap` (예: `msg.image`) → PNG로 보냄 |
| 배열 | 위 값들의 배열. 한 번에 여러 파일을 보냅니다. |

파일 형식은 내용(시그니처)으로 판별하고, URL·`Content-Disposition`·`Content-Type`·파일명은 판별을 좁히는 데 씁니다
(예: ZIP 시그니처 + `.docx` URL → docx). 알 수 없는 형식은 텍스트처럼 보이면 `txt`, 아니면 `bin`으로 보냅니다.

### 옵션

`MediaSharer.send(room, files, options)`의 `options` (모르는 키는 오타로 보고 거부합니다):

| 키 | 설명 |
|---|---|
| `fileName` | 보낼 파일 이름(확장자는 자동으로 붙음). 여러 파일이면 `이름_1`, `이름_2`, … |
| `timeout` | 다운로드 제한 시간(ms). 연결과 데이터 대기마다 적용됩니다. 없으면 설정값. |
| `cache` | URL·Base64·바이트를 캐시에 보관해 다시 보낼 때 재사용할지. 없으면 설정값. |
| `headers` | 다운로드 요청 헤더 (예: `{ Referer: "https://example.com" }`) |
| `leave` | 카카오톡이 파일을 읽으면 나갈 곳: `"home"`(또는 `true`), `"starlight"`, 패키지 이름. `"stay"`(또는 `false`)는 머묾. 없으면 설정값. |

### MediaSharer

| 메서드 | 설명 |
|---|---|
| `send(room, files[, options])` | 보내기를 시작하고 결과(`SendResult`)를 돌려줍니다. 실패해도 reject하지 않고 `ok: false`로 끝납니다. |
| `leave([to[, delay]])` | 카카오톡에서 나가 `to`(`"home"` 기본, `"starlight"`, 패키지 이름)로 이동합니다. `delay`가 없으면 마지막 전송의 파일을 카카오톡이 읽은 직후(준비 중인 전송이 있으면 그것까지), 있으면 `delay`ms 뒤. `leave(0)`처럼 숫자만 주면 지연입니다. |
| `clearCache([target])` | 캐시 삭제. 없으면 전체, URL·캐시 키·캐시 파일명 또는 그 배열이면 해당 항목만. 지운 개수를 돌려줍니다. |
| `getRooms()` | 알려진 방 목록 (`RoomInfo`) |
| `findRoom(name)` | 그 이름으로 가장 최근에 본 방 (`RoomInfo`), 없으면 `null` |
| `hasOverlayPermission()` | '다른 앱 위에 표시' 권한 여부 |

**SendResult**: `ok`(카카오톡이 파일을 받았는지), `room`, `sent`(보낸 파일 수), `failed`(빠진 파일 수), `error`(아무것도 못 보낸 이유),
`errors`(빠진 파일별 이유 목록), `via`(듀얼 메신저면 `"user 95"`, 선택창이면 `"chooser"`).

**RoomInfo**: `id`(채널 ID), `name`, `isGroupChat`, `lastSeen`, `userId`(듀얼 메신저면 95).

여러 파일 중 일부만 실패하면 나머지는 보내고(`ok: true`, `failed > 0`) 실패한 파일은 프로젝트 로그에 경고로 남깁니다.
하나도 준비하지 못하면 전송하지 않습니다(`ok: false`).

### 테스트 스크립트

[`examples/neonjs/MediaSharerTest.js`](examples/neonjs/MediaSharerTest.js)는 위 기능을 명령(`!ms <이름>`)별로 시험하고
PASS/FAIL을 답장하는 NeonJS 프로젝트 스크립트입니다. 준비물 없이 동작합니다(이미지는 파일 안의 PNG 데이터, 로컬 파일은
`project.json`). 단위 테스트(`ExampleScriptTest`)가 이 스크립트를 NeonJS에서 실행해 모든 명령이 PASS하는지 확인합니다.

## 설정

- **상태**: 카카오톡 설치 여부, '다른 앱 위에 표시' 권한(눌러서 권한 설정 열기)
- **전송**: 다운로드 제한 시간, 최대 파일 크기, 연속 전송 간격, 임시 파일 보관 시간, 전송 후 동작(머물기 / 홈 화면 / StarLight로 돌아가기), 카카오톡 최대 체류 시간, 파일을 읽은 뒤 대기, 전송 로그
- **캐시**: 기본 캐시 사용 여부, 최대 파일 수·크기, 캐시 비우기(사용량 표시)
- **채팅방**: 메시지를 받은 방 목록(이름 — 채널 ID), 목록 지우기
- **기록**: 최근 전송 30건(시간, 방, 파일 수, 결과, 소요 시간)

'다른 앱 위에 표시' 권한이 없으면 Android가 백그라운드에서 카카오톡을 여는 것을 막아, StarLight가 화면에 떠 있을 때만 보낼 수 있습니다.

## 카카오톡 체류 시간

카카오톡은 채팅방이 열려 있는 동안 알림을 보내지 않으므로, 그동안 온 메시지는 StarLight가 받지 못합니다.
그래서 전송 후에는 고정된 시간 대신 **카카오톡이 파일을 다 읽은 순간**을 기다려 바로 나갑니다.

- 카카오톡은 StarLight의 FileProvider로 파일을 읽고, 파일은 StarLight 프로세스에서 열립니다. 공유한 파일에 `FileObserver`(inotify)를
  걸어 두고, 모든 파일이 열렸다가 닫히면 읽기가 끝난 것으로 봅니다.
- 그 뒤 **파일을 읽은 뒤 대기**(기본 0.5초)만큼 더 기다려 카카오톡이 메시지를 올릴 시간을 주고 나갑니다.
- `send`의 `leave` 옵션, 설정의 "전송 후", 지연 없이 부른 `MediaSharer.leave()`가 모두 이 방식으로 나갑니다.
- 읽는 것이 감지되지 않으면(예: 이벤트가 오지 않는 공유 저장소의 파일) **최대 체류 시간**(기본 5초) 뒤에 나갑니다.
- 선택창이 뜬 전송은 사용자가 고를 때까지 나가지 않습니다(나가면 선택창이 닫히므로).
- 전송 로그를 켜면 카카오톡이 파일을 읽는 데 걸린 시간이 프로젝트 로그에 남습니다. 이 값을 보고 대기 시간을 조절하세요.

## 듀얼 메신저(카카오톡 2개)

Samsung 듀얼 메신저는 카카오톡을 앱 복제 프로필(user 95)에 하나 더 설치합니다. 방마다 알림이 온 사용자를 기록해,
그 방의 카카오톡으로 보냅니다(설정 > 채팅방 목록에 `[사용자 95]`로 표시).

- **StarLight와 같은 사용자(user 0)의 방**: 바로 전송됩니다. Samsung `DualAppManagerService`는 카카오톡을 패키지나
  컴포넌트로 지정한 SEND Intent를 무조건 "어느 카카오톡?" 선택창으로 바꾸기 때문에, 카카오톡을 selector로 지정해 피합니다.
- **다른 사용자(user 95)의 방**: 다른 사용자의 앱을 여는 것은 `INTERACT_ACROSS_USERS` 권한이 있어야 합니다. StarLight가 이 권한을
  매니페스트에 선언하고 아래처럼 부여받으면 바로 전송하고, 아니면 선택창이 뜨며 프로젝트 로그에 이유가 남습니다.
  ```sh
  adb shell pm grant dev.mooner.starlight android.permission.INTERACT_ACROSS_USERS
  ```
  이 경로는 hidden API인 `Context.startActivityAsUser`를 리플렉션으로 호출하므로, 기기의 hidden API 정책에 따라 막힐 수 있습니다
  (막히면 역시 선택창으로 보내고 이유를 로그에 남깁니다).
- 알림을 아직 받지 못한 방(채널 ID로만 지정)은 어느 카카오톡인지 알 수 없어 선택창이 뜹니다. 그 방에서 메시지를 한 번 받으면 됩니다.

## 원래 모듈에서 옮겨 오기

| 원래 모듈 (`MediaSender`) | 이 플러그인 (`MediaSharer`) |
|---|---|
| `const MediaSender = require("mediaSender")` | 필요 없음 (전역 `MediaSharer`) |
| `MediaSender.send(channelId, path)` → `true`/`false` | `MediaSharer.send(msg, path)` → 결과 객체 (NeonJS `await`, Rhino `.join()`) |
| `send(channelId, path, timeout, fileName, saveCache)` | `send(room, files, { timeout, fileName, cache })` |
| `MediaSender.return(packageName, delay)` | `MediaSharer.leave(to, delay)`, 또는 `send` 옵션 `leave` / 설정의 "전송 후" |
| `MediaSender.clearCache(target)` → `true` | `MediaSharer.clearCache(target)` → 지운 개수 |

## 원래 모듈과 달라진 점

- **비동기 전송**: `send()`가 다운로드를 기다리지 않고 바로 반환합니다. 결과는 Promise(NeonJS)·CompletableFuture(Rhino)로 받습니다.
- **카카오톡에서 바로 나가기**: 고정 지연 대신 카카오톡이 파일을 읽은 직후 나갑니다(아래 "카카오톡 체류 시간").
- **저장 위치**: `sdcard/msgbot_media/` 대신 StarLight 내부 저장소(`files/plugin_data/mediasharer/`)를 쓰고, StarLight의
  FileProvider(`dev.mooner.starlight.provider`)로 공유합니다. 저장소 권한과 미디어 스캔이 필요 없고 갤러리에 흔적이 남지 않습니다.
  공유 저장소의 파일은 복사하지 않고 그대로 공유합니다.
- **병렬 다운로드**: multiTask 모듈 없이 여러 파일을 동시에 준비합니다. 같은 경로·URL은 한 번만 내려받습니다.
- **캐시**: 메타데이터를 메모리에 두고 한 번에 저장하며, 파일 수와 함께 **전체 크기**로도 제한합니다. KakaoTalk이 읽고 있을
  파일(임시 파일 보관 시간 이내에 쓴 파일)은 지우지 않습니다.
- **형식 판별**: 다운로드 전에 따로 요청하던 시그니처 확인을 없애고 받은 내용으로 판별합니다. HEIC·AVIF·MOV·M4A 구분,
  `?format=jpg` 같은 쿼리, `Content-Disposition` 파일명을 지원하고, 텍스트가 TS·BMP로 오인되던 문제를 고쳤습니다.
  알 수 없는 형식은 `jpg`가 아니라 `txt`/`bin`으로 보냅니다.
- **파일 이름**: 전송마다 별도 폴더를 써서 동시에 보내도 이름이 겹치지 않습니다. URL·`Content-Disposition`의 파일명을 살립니다.
- **공유 대상 지정**: 카카오톡을 Intent의 패키지·컴포넌트가 아니라 selector로 지정합니다(아래 "듀얼 메신저").
- **방 이름**: 메신저봇의 NotificationSession 대신 플러그인이 받은 메시지로 방 목록을 만들고 재시작 후에도 유지합니다.
- **복귀**: 패키지 가시성 제한으로 `getLaunchIntentForPackage`가 실패해도 런처 Intent로 앱을 엽니다.

## 빌드

```sh
./gradlew :app:assembleReleaseSlp   # app/build/outputs/starlight/release/mediasharer-release.slp
./gradlew :app:testReleaseUnitTest  # 형식 판별, 입력 해석(NeonJS 포함), 캐시, 다운로드, 방 해석 테스트
./gradlew :app:deployReleaseSlp -Pstarlight.device=<serial>
```

JDK 17~21로 실행하세요(Gradle 8.11.1은 더 새로운 JDK에서 동작하지 않음). `app/libs`의 `PluginCore-release.aar`,
`ConfigDSL-release.aar`은 StarLight 저장소에서 빌드한 것입니다(`./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease`).

`assets/` 아래 파일(아이콘 등)을 바꾸면 `starlight.json`의 `assetRevision`을 올려야 기존 설치본에 반영됩니다.

## 출처

원래 구현: MediaSender 모듈 v1.5.1 — Hehee, [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
카카오톡 공유 Intent(`key_id`, `key_type`, `key_from_direct_share`), 텍스트 파일을 `ACTION_SEND_MULTIPLE`로 보내는 처리,
파일 시그니처 표 등은 원래 모듈을 따랐습니다.
