# Logent 사용자 지정 OpenAI

OpenAI 호환 Chat Completions 서버를 Logent의 AI 백엔드로 등록하는 플러그인이에요.

## 설정

- `endpoint`: `https://`로 시작하는 Chat Completions 주소예요. 예: `https://api.openai.com/v1/chat/completions`
- `API 키`: 플러그인 전용 저장소에만 보관해요.

설정 → AI와 검색 → AI 백엔드에서 **사용자 지정 OpenAI**를 고르고 모델을 입력해요.
모델은 provider id별로 저장되며, 실행을 시작하면 endpoint·키·모델·세션이 고정돼요.

## 빌드

```bash
LOGENT_CONTENT_SIGNING_KEY_FILE="$HOME/.local/share/logent/content-signing.pem" \
  ./scripts/build.sh -PlogentPluginApiMavenLocal=true
```

기본 API는 `plugin-api.lock`의 4.1.0 artifact를 사용해요. 로컬 API 개발은
`-PlogentPluginApiSource=true` 또는 `-PlogentPluginApiMavenLocal=true`를 명시해요.
