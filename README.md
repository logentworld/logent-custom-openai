# Logent AI 제공자

OpenAI 호환 서버·NVIDIA NIM·Codex 구독·OpenCode Go 구독을 여러 프로필로 등록하는 플러그인이에요. 저장소와 플러그인 ID는 `logent-custom-openai`·`com.sleepysoong.logent.custom-openai`를 유지해요.

## 시작하기

1. API **4.4.0**을 포함한 Logent에 서명된 `.lpg`를 설치해요.
2. **설정 → 플러그인과 스킬 → 플러그인 관리 → AI 제공자 → 제공자 추가**에서 이름·종류·endpoint·모델·선택 인증을 저장해요. 프로필은 최대 32개까지 추가·수정·삭제할 수 있어요.
3. 모델 설정에서 해당 프로필을 선택해요. 키와 프로필별 모델은 위 프로필에서 관리하며, 모델 화면에서 고른 모델이 있으면 그 값을 우선해요.

프로필마다 서버·모델·비밀값이 격리돼요. 이름이나 순서를 바꿔도 항목 ID와 제공자 ID는 그대로 유지해요. 저장한 변경은 새 기여 세대와 다음 실행부터 적용하고, 이미 시작한 실행은 고정한 endpoint·모델·인증·대화 ID를 계속 써요. 실행 도중 프로필을 삭제해도 다른 제공자로 전환하지 않아요.

| 필드               | 설정 방법                                                                                                                                      |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 이름               | 모델 설정에 표시할 프로필 이름이에요.                                                                                                          |
| 종류               | `OpenAI 호환`·`NVIDIA NIM`·`Codex 구독` 중 하나예요. OpenCode Go는 `OpenAI 호환`으로 등록해요.                                                 |
| Endpoint           | 사용자 정보가 없는 HTTPS 주소예요. OpenAI 호환·NVIDIA는 `.../v1` base 또는 전체 Chat Completions 주소를, Codex는 전체 Responses 주소를 넣어요. |
| 모델               | 해당 서버가 지원하는 실제 모델 ID예요. 요청 중 다른 모델로 자동 변경하지 않아요.                                                               |
| API 키             | 선택 비밀값이에요. OpenAI 호환에서 비우면 `Authorization`을 보내지 않아요. NVIDIA에는 키가 필요하고, Codex는 이 필드 대신 인증 JSON을 써요.    |
| Codex 인증 JSON    | 선택 비밀값이에요. Codex 로그인 토큰 JSON을 넣거나 비워서 최초 요청 때 Termux의 `~/.codex/auth.json`을 읽어요.                                 |
| OpenCode Go 사용량 | OpenCode Go 프로필이면 플러그인이 대화를 실행할 때 5시간·주간·월간 남은 사용량과 리셋 시각을 자동으로 채워요. 직접 고칠 필요가 없어요.         |

OpenAI 호환 기본값은 `https://api.openai.com/v1`·`gpt-4.1-mini`예요. 종류를 NVIDIA나 Codex로 바꿨을 때 endpoint·모델이 비어 있거나 이 OpenAI 기본값 그대로면 아래 종류별 기본값으로 해석해요. 직접 입력한 다른 값은 그대로 써요.

- **NVIDIA NIM**: `https://integrate.api.nvidia.com/v1`·`deepseek-ai/deepseek-v4.1-flash`예요. `build.nvidia.com`에서 발급한 키와 사용할 모델 ID를 프로필에 저장해요.
- **Codex 구독**: `https://chatgpt.com/backend-api/codex/responses`·`gpt-5.6-sol`예요. ChatGPT 계정의 Codex 구독과 사용 가능한 모델 ID가 필요해요. Termux에서 `codex login`을 실행하거나 해당 계정의 인증 JSON을 프로필에 넣어요. 여러 계정은 서로 다른 프로필의 인증 JSON에 각각 넣어요.

Codex는 CLI의 `tokens` 중첩 형태와 최상위 `access_token`·`refresh_token` 형태를 모두 읽어요. 최초 요청 때만 파일을 읽고 가져온 인증·회전된 refresh 토큰은 프로필의 SECRET `auth-json`에 저장해요. 갱신은 프로필별로 직렬화하며, 사용자가 인증을 교체하거나 프로필을 삭제한 뒤 도착한 갱신은 원자 비교 후 저장하지 않아요. 토큰을 셸 명령 인자·프롬프트·로그로 보내지 않아요. 비밀값은 설정 내보내기에서 제외돼요.

## OpenCode Go

Go 구독은 일반 종량제와 게이트웨이 경로가 달라서, 아래 세 가지만 맞추면 바로 동작해요.

- **종류**는 `OpenAI 호환`으로 두고, **Endpoint**에 다음 중 하나를 넣어요.
  - `https://opencode.ai/inference/go` (권장 base)
  - `https://opencode.ai/inference/go/openai/v1/chat/completions`
  - `https://opencode.ai/zen/go/v1` 또는 `https://opencode.ai/zen/go/v1/chat/completions`
- Go를 구독한 워크스페이스의 개인 API 키를 `Bearer` 없이 **API 키**에 넣고, 키 권한의 **Models: Request**를 허용해요.
- **모델**은 `opencode-go/` 접두사 없이 `deepseek-v4.1-flash`·`glm-5.3`·`kimi-k3`처럼 Go가 제공하는 ID를 넣어요. Go endpoint에서는 이 칸이 비어 있거나 OpenAI 기본값(`gpt-4.1-mini`)이면 `deepseek-v4.1-flash`로 해석하고, 모델 화면에서 고른 모델이 있으면 그 값을 써요. 모델 선택 화면에는 Go 모델 목록을 함께 보여 줘요.

Go endpoint에만 실제 대화 ID의 `x-opencode-session`과 자체 `User-Agent`를 붙여요. API의 `protectedHeaders`로 다른 출처 리다이렉트에 대화 ID를 넘기지 않아요. 호스트가 대화 ID를 주지 않는 구형 빌드에서는 프로필별로 고정된 안전한 세션 ID로 대체해 요청이 통째로 막히지 않게 해요.

### 사용량 표시

Go 프로필은 대화를 실행할 때 `GET /v1/usage`를 함께 읽어 **5시간·주간·월간 남은 사용량과 리셋 시각**을 프로필의 `OpenCode Go 사용량` 필드에 채워요. 예: `5시간 98% 남음(2시간 21분 뒤) · 주간 70% 남음(11/16 09:00) · 월간 63% 남음(12/8 20:45)`. 서버를 자주 부르지 않도록 60초 동안은 마지막 값을 다시 써요. 사용량 조회가 실패해도 대화는 그대로 진행해요.

### 오류 안내

Go 오류는 원인과 해결책을 함께 알려 줘요.

- `Model is unavailable`: 모델 ID가 Go 목록에 없어요. 프로필의 모델을 Go 모델 ID로 바꿔 주세요.
- `401`: 키가 거부됐어요. 키와 **Models: Request** 권한을 확인해 주세요.
- `403`: 모델 접근 권한이나 워크스페이스 Privacy의 Regions 설정(예: DeepSeek V4.1 Flash는 Global)을 확인해 주세요.
- `404`: endpoint 경로가 달라요. 위 base 주소 중 하나로 맞춰 주세요.
- `429`: 요청 한도를 넘었어요. 잠시 뒤 다시 시도해 주세요.

일반 `/inference/openai/...`는 구독이 아닌 종량제 경로예요. `402 Insufficient account funds`에는 Go 주소 안내를 표시해요. 일반 종량제·다른 모델·다른 제공자·외부 런타임으로 자동 우회하지 않아요.

[공식 Go 안내](https://opencode.ai/docs/console/go)와 [하네스·세션 안내](https://opencode.ai/docs/console/harnesses#sessions)를 참고해요.

## 개발

프로덕션 의존성은 `plugin-api.lock`의 API `4.4.0` artifact뿐이에요. manifest의 `apiMin`·`apiMax`는 정수 major 계약인 `4`를 사용하며, 반복 설정·조건부 인증 저장에는 **4.4.0 이상** 호스트가 필요해요. 코어 내부 클래스·직접 HTTP 클라이언트를 사용하지 않고 공개 `PluginHost.http`·`httpStream`·설정 API만 사용해요. 기존 단일 endpoint·키 설정이나 내장 NVIDIA·Codex 설정의 변환 경로는 제공하지 않아요.

```bash
LOGENT_CONTENT_SIGNING_KEY_FILE="$HOME/.local/share/logent/content-signing.pem" \
  ./scripts/build.sh -PlogentPluginApiMavenLocal=true
```

기본은 고정 Maven artifact를 사용하고, 로컬 API 개발은 `-PlogentPluginApiSource=true` 또는 `-PlogentPluginApiMavenLocal=true`를 명시해요. 소비자 회귀 테스트는 `./scripts/test.sh -PlogentPluginApiSource=true`로 실행해요. 프로필 격리·인증 누락·안전한 세션 ID·SSE 완료·도구 스키마(`type` 포함)·Go 사용량 파싱·모델 기본값·오류 문구·Codex 토큰 회전·늦은 저장·취소 경계만 검사하고 실제 유료 API는 호출하지 않아요. 스크린샷 테스트는 사용자가 명시적으로 요청할 때만 실행해요.
