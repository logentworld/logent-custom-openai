# Logent 사용자 지정 OpenAI

OpenAI 호환 Chat Completions 서버를 Logent의 AI 백엔드로 등록하는 플러그인이에요.

## 설정

- `endpoint`: `https://`로 시작하는 Chat Completions 주소예요. 예: `https://api.openai.com/v1/chat/completions`
- `API 키`: 플러그인 전용 저장소에만 보관해요.

`endpoint`는 **설정 → 플러그인과 스킬 → 플러그인 관리 → 사용자 지정 OpenAI**의 플러그인 설정에서 바꿔요.
설정 → AI와 검색 → AI 백엔드에서는 **사용자 지정 OpenAI**를 고르고 API 키와 모델을 저장해요.
모델은 provider id별로 저장되고, 코어는 실행 시작에 키·모델을 고정해요.

## OpenCode Go

- `endpoint`에 전체 Chat Completions 주소를 넣어요.
  - `https://opencode.ai/inference/go/openai/v1/chat/completions`
  - `https://opencode.ai/zen/go/v1/chat/completions`
- Go를 구독한 워크스페이스의 기존 개인 API 키를 그대로 쓸 수 있어요. `Bearer` 없이 키 값만 저장하고, 키 권한의 **Models: Request**를 허용해요.
- 모델은 `opencode-go/` 같은 접두사 없이 `deepseek-v4.1-flash`·`glm-5.3`·`kimi-k3`처럼 Go가 제공하는 ID를 입력해요.
- 플러그인이 대화별 고정 `x-opencode-session`과 자체 `User-Agent`를 자동으로 보내요. 실제 대화 ID가 없거나 안전하지 않으면 전송 전에 오류를 알려줘요. 다른 endpoint에는 이 세션 헤더를 보내지 않아요.
- 다른 출처로 리다이렉트되면 대화 ID 헤더를 넘기지 않도록 플러그인이 API 4.3의 `protectedHeaders`로 보호를 선언해요.
- Console의 API 변환을 이용하므로 Chat Completions를 유지하며, 일반 종량제 경로나 다른 모델로 자동 전환하지 않아요.

[공식 Go 안내](https://opencode.ai/docs/console/go)와 [하네스·세션 안내](https://opencode.ai/docs/console/harnesses#sessions)를 참고해요.

## 빌드

```bash
LOGENT_CONTENT_SIGNING_KEY_FILE="$HOME/.local/share/logent/content-signing.pem" \
  ./scripts/build.sh -PlogentPluginApiMavenLocal=true
```

기본 API는 `plugin-api.lock`에 고정된 artifact를 사용해요. API `4.3.0`이 포함된 Logent `0.0.105` 이상이 필요해요. 로컬 API 개발은
`-PlogentPluginApiSource=true` 또는 `-PlogentPluginApiMavenLocal=true`를 명시해요.
