# 이관 검증 보고서 1 — `/api/items/search`

- 검증 대상: `git diff upstream/main...HEAD` (커밋 4개, 22개 파일, +4423줄)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준: `templates/approval-checklist.md` v0, `CLAUDE.md`
- 코드는 수정하지 않았다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 미충족 | `cd modern/api && ./gradlew cleanTest test` → 53건 통과 · 0 실패 · 0 오류 · 0 skip (`BUILD SUCCESSFUL`). 이관 코드에 해당하는데도 `characterization` 은 실행하지 않음 (레거시 8081 · 새 API 8080 모두 응답 없음). 체크리스트 1번(변경 모듈 테스트를 실제로 실행)과 이관 동작 보존 검증이 끝나지 않았다. |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 없음, SQL 문자열 연결 없음(Specification 바인딩), 삭제 · 덮어쓰기 코드 없음. |
| 요청 범위 이탈 없음 | 미충족 | 이슈 #1. 요청은 "엔드포인트 1개 이관"인데 22개 파일 중 `.claude/`(4), `CLAUDE.md`, `docs/item-bank/`(4)는 이관과 무관하다. |
| 컨벤션 준수 | 충족 | 위반 없음. 경고 · 제안만 있다(#2, #3). |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `.claude/settings.json:1`, `.claude/hooks/eslint-web.mjs:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `.claude/skills/verify-changes/SKILL.md:1`, `CLAUDE.md:1`, `docs/item-bank/ARCHITECTURE.md:1`, `docs/item-bank/BUSINESS-RULES.md:1`, `docs/item-bank/CROSS-CHECK.md:1`, `docs/item-bank/ERD.md:1` | 요청 문장("엔드포인트 1개 이관")으로 설명되지 않는 파일이 10개다. 체크리스트 3번 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다"를 어긴다. 이관 자체의 파일은 `modern/api` 10개와 `characterization/tests/` 2개(스냅샷 · 테스트)다. 이 브랜치가 1회차 작업 전체(Hook · Skill · 문서화 · 베이스라인)를 담고 있어 생긴 것으로 보인다. | 이관 변경만 따로 브랜치(PR)로 나누거나, 나머지 파일이 별도 요청의 산출물이라는 점을 PR 설명에 적는다. |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCriteria.java:286` (비교: `legacy/item-bank-php/search.php:54`) | 레거시는 `q[]=a&q[]=b` 처럼 배열로 오면 첫 값을 쓴다(`reset`). 새 코드는 키 이름이 `q[]` 라 `params.get("q")` 가 비어 `""` 로 처리한다. 같은 요청의 결과가 달라질 수 있다(동작 보존 위반 가능). 같은 이름을 반복한 `q=a&q=b` 는 둘 다 마지막 값이라 같다. 이 경우를 다루는 테스트는 찾지 못했다. | 배열 형태(`name[]`) 파라미터의 레거시 동작을 `characterization` 케이스로 먼저 확인하고, 다르면 `lastValue` 에서 `name[]` 키의 첫 값을 처리하거나 의도된 차이로 문서에 적는다. |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchResponse.java:134` | 레거시 화면은 경고 문구(`$warnings`, 예: "단원 코드 형식이 올바르지 않습니다")와 조건 요약을 보여 주지만 응답 JSON 은 `rows · count · message` 만 담는다. 동작 보존 테스트의 정규화 결과와 맞춘 설계로 보이나 의도는 문서에 없다. | 경고 · 요약을 의도적으로 뺐다는 점을 `docs/item-bank/` 또는 PR 설명에 한 줄 남긴다. |

## 확인 필요

- **새 API 대상 동작 보존 테스트 결과**: `cd characterization && npm test` (레거시) 와 `TARGET_BASE_URL=http://localhost:8080 npm test` (새 API) 를 실행하지 못했다. 두 서버가 모두 꺼져 있다. `docker compose --profile <이름>` 으로 레거시 · MariaDB 를, `cd modern/api && ./gradlew bootRun` 으로 새 API 를 기동해 주면 실행해 통과 · 실패 수를 이 보고서에 추가하겠다(스냅샷 `-u` 는 쓰지 않는다). 이 결과가 없으면 "동작을 바꾸지 않았다"를 입증할 수 없다.
- **`.claude/skills/verify/`**: 추적되지 않은 파일이라 diff 에 없다. 커밋 대상인지 확인이 필요하다.

## 확인한 항목 요약

- 컨트롤러는 Service 만 호출한다. `*Repository` 필드 · SQL 이 없다(`ItemSearchController.java:17`).
- 노출 문항은 `status = 'A'` 만 조회한다(`ItemSearchSpecifications.java:23`, 레거시 뷰 `db/mariadb/init/01-schema.sql:70`과 일치).
- `level < 5` 기본 제외, 페이지 1~999 보정, 키워드 100자 · 태그 50자 절단, 20건 페이지 크기는 레거시(`search.php:~75-125, 335-350`)와 일치한다.
- 태그는 `in :itemIds` 한 번의 쿼리로 읽어 N+1 이 없다(`ItemSearchService.java:83`).
- 빈 `catch` · `System.out` · `printStackTrace` · 인자 없는 `now()` · 학생 식별자 로그 없음. 120자 초과 줄 없음.
- 의존성(`build.gradle`) · `application.yml` · DB 스키마 · 시드 변경 없음.
- 새 엔드포인트와 public 메서드마다 테스트가 있다(컨트롤러 4, 서비스 5, 조건 정제 12, 저장소 8). `@Disabled` · skip 없음.
