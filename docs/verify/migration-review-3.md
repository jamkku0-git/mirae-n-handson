# item-bank 검색 이관 검증 보고서 3

- 검증 대상: `git diff upstream/main...HEAD -- modern characterization` (커밋 5892aff 베이스라인 · 283a01e 이관, 파일 12개 +3000줄)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 이관 대상: `legacy/item-bank-php/search.php` → `GET /api/items/search` (`characterization/lib/target.mjs:22` 의 `PATH_ALIASES`)
- 기준: `templates/approval-checklist.md` v1, `CLAUDE.md`
- 검증일: 2026-10-06
- 검증 세션 주의: 이 세션은 같은 날 `ItemSearchCriteria.java` 를 수정(커밋 안 함, 이슈 #3)했다. CLAUDE.md "변경을 만든 세션이 스스로 승인하지 않는다"에 따라, 이 보고서는 승인 판정 근거로 쓰지 않고 최종 승인은 새 세션에서 한다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 미충족 | `cd modern/api && ./gradlew test` 53 통과 · 0 실패 · 0 건너뜀(작업 트리 기준, 커밋 안 된 #3 수정 포함). `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`(item-bank) 24 통과 · 0 실패. 레거시 대상 `cd characterization && npm test` 는 실행하지 않음(8081 레거시 서버가 꺼져 있음). 이관 코드라 두 대상 모두 필요하므로 미충족. |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 · 로그 노출 없음, SQL 은 Criteria API 바인딩(`ItemSearchSpecifications.java:27-30`), 업로드 · 다운로드 · 인증 변경 없음. |
| 요청 범위 이탈 없음 | 충족 | 12개 파일(Controller · Criteria · Repository · Response · Service · Specifications, 테스트 4개, characterization 테스트 · 스냅샷)이 모두 `/search.php` 이관으로 설명된다. 의존성 · `application.yml` · `db/*/init/` · `characterization/lib/` 변경 없음. |
| 컨벤션 준수 | 충족 | 컨트롤러 계층(`ItemSearchController.java:17` 필드는 Service 뿐) · 빈 catch · 임시 출력 · N+1(`ItemSearchRepository.java:19` `@EntityGraph`, 태그는 `ItemSearchService.java:55` `in` 쿼리 1회) 위반 없음. 경고 · 제안만 있음. |

## 실행 기록

| 명령 | 대상 | 결과 |
|---|---|---|
| `cd modern/api && ./gradlew test` | 작업 트리(HEAD + 커밋 안 된 `ItemSearchCriteria.java` 수정) | 53 통과 · 0 실패 · 0 건너뜀 |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npx vitest run tests/item-bank.test.js` | 8080 새 API. 서버 기동 13:18 로 #3 수정(16:50) 이전이므로 커밋된 코드 기준 | 24 통과 · 0 실패. 스냅샷 변경 없음(`-u` 쓰지 않음) |
| `cd characterization && npm test` (레거시 대상) | 8081 레거시 | 실행하지 않음 — 레거시 서버 꺼짐 |

## 이슈 목록

줄번호는 검증 대상 커밋(HEAD) 기준이다.

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `characterization/tests/item-bank.test.js:120` | 이름은 "태그를 배열로 — 첫 값만 사용"이지만 `characterization/lib/target.mjs:42` 가 배열을 `tag=계산&tag=개념` 로 보내므로 레거시(PHP)는 마지막 값을 쓴다. BR-15(배열이면 첫 값, `search.php:51-80` 의 `reset()`)는 실제로 검증되지 않는다. | 테스트 이름을 "같은 이름 반복 — 마지막 값"으로 바꾸고 `tag[]` 케이스를 따로 추가한다. 스냅샷을 다시 찍어야 하므로 먼저 사람에게 알린다. |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCriteria.java:89-95` | 레거시는 `tag[]=a&tag[]=b` 처럼 대괄호 파라미터를 배열로 받아 첫 값을 쓴다(`legacy/item-bank-php/search.php:51-80`, 파라미터 7개 모두). 새 코드는 키 `name` 만 읽으므로 `name[]` 조건이 빠진다. characterization 에 케이스가 없어 이번 실행으로 드러나지 않았다. | `name[]` 케이스를 characterization 에 추가해 레거시 동작을 먼저 고정한 뒤, 맞출 범위를 정하거나 의도된 차이로 문서화한다. |
| 3 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCriteria.java:30,66` | PHP `preg_match('/^[0-9]+$/')` 는 끝 개행 앞에서도 맞으므로 레거시는 `page=5%0A` 를 5페이지로 본다(`search.php:337-338`). 커밋된 코드는 1페이지로 본다. 작업 트리에 `[0-9]+\n?` 로 고친 수정이 있으나 커밋되지 않았고, 이를 검증하는 테스트도 없다. | 수정을 커밋하고 `ItemSearchCriteriaTest` 에 `5\n`→5, `\n`·`5\n\n`→1 케이스를, characterization 에 `page=5%0A` 케이스를 추가한다. |
| 4 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchController.java:25-27` | 레거시는 DB 연결 · 검색 실패 시 HTTP 200 에 오류 문구 HTML 을 낸다(`search.php:712-729`). 새 API 는 `GlobalExceptionHandler` 로 500 을 낸다. CLAUDE.md 의 예외 규칙(그 밖 500)에는 맞으므로 코드 수정 대상은 아니다. | 의도된 차이로 `docs/item-bank/` 에 한 줄 남긴다. |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCriteria.java:12-14` | 레거시 규칙을 옮긴 주석이 `search.php` 만 언급하고 줄번호 · BR 번호가 없다(CLAUDE.md "근거를 `파일:줄번호` 로"). 근거는 `docs/item-bank/BUSINESS-RULES.md` 에 있다. | 보정 로직마다 BR 번호나 `search.php:줄번호` 를 단다. |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchResponse.java:9` | 레거시 화면의 조건 요약(`search.php:352-367`) · 경고 문구(`search.php:508-516`)가 응답에 없다. 정규화 결과 형식({rows, count, message})에 맞춘 것으로 보이나 문서화되지 않았다. | 뺀 이유를 `docs/item-bank/` 에 남긴다. |

## 확인 필요

- 레거시 대상 characterization 실행: `docker compose --profile <레거시 프로필> up -d` 로 8081 을 띄운 뒤 `cd characterization && npm test` 를 실행하면 "테스트 통과" 기준을 판정할 수 있다.
- 이관 대상 선택: 요청 문장에 엔드포인트 이름이 없다. README.md:115-116 에 따르면 `/api/items/search` 는 1-3 차시 실습 산출물이다. 사람이 이 엔드포인트를 지정했는지 확인하면 범위 판정이 확정된다.
- 이슈 #2 · #4 · #6 을 "의도된 차이"로 받아들일지 사람의 결정이 필요하다.
- 범위 밖 작업 트리 변경: `.claude/settings.json` 수정, `.claude/agents/`, `docs/verify/vendor-pr-verdicts.md` 가 커밋되지 않은 채로 있다. 이 변경분과 함께 커밋할지 확인이 필요하다.
