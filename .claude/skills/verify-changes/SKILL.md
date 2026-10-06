---
name: verify-changes
description: 변경된 모듈(modern/api, modern/web, characterization, mcp-skeleton)을 골라 그 모듈의 테스트 · 린트 · 타입 검사를 실행하고 통과 · 실패 수를 보고한다. 작업을 "끝났다"고 말하기 전, 또는 사용자가 검증을 요청할 때 사용한다.
---

# 변경 모듈 검증

1. `git status --porcelain` 과 `git diff --name-only HEAD` 로 바뀐 파일을 모은다. 인자(`$ARGUMENTS`)로 모듈을 지정했으면 그 모듈만 검사한다.
2. 바뀐 경로에 해당하는 모듈만 아래 명령으로 검사한다. 저장소 루트 기준 경로다.

| 경로 | 명령 |
|---|---|
| `modern/api/` | `cd modern/api && ./gradlew test` |
| `modern/web/` | `cd modern/web && npm run lint && npm run typecheck && npm test` (`node_modules` 가 없으면 먼저 `npm ci`) |
| `characterization/` | `cd characterization && npx vitest run tests/normalize.unit.test.js` — 서버가 필요한 나머지 테스트는 대상 서버가 떠 있을 때만(`curl -s -o /dev/null -w "%{http_code}" http://localhost:8081` 이 200/302) `npm test` |
| `mcp-skeleton/` | `cd mcp-skeleton && npm run build` |
| `legacy/`, `db/` | 검사 명령 없음 — 수정 자체가 승인 대상이므로 사용자에게 알린다 |

3. 보고 형식 — 모듈마다 한 줄:
   `modern/api: 27 통과 · 0 실패` / `modern/web: lint 0 오류 · typecheck 통과 · 12 통과 · 0 실패` / `characterization: 실행하지 않음 (서버 꺼짐)`
4. 실패가 있으면 실패한 테스트 이름과 핵심 오류 메시지를 붙인다. 이 skill 안에서 코드를 고치지 않는다 — 고칠지는 사용자에게 묻는다.
5. 스냅샷(`characterization/__snapshots__/`)이 바뀌었으면 반드시 따로 알린다. `-u` 로 스냅샷을 갱신하지 않는다.
