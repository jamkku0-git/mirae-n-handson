# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Claude Code 심화 과정 실습 저장소. 모듈 · 포트 · DB 계정 · compose 프로필은 `README.md` 를 본다. 데이터는 전부 더미다.

- 답변과 문서는 한국어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이다. 허락 없이 수정하지 않는다.

## 1. 빌드 · 테스트 명령

### modern/api (Spring Boot 3 · Java 21)

```bash
cd modern/api && ./gradlew test                                          # DB 없이 통과해야 한다 (H2 MariaDB 모드)
cd modern/api && ./gradlew test --tests 'com.example.item.UnitServiceTest' # 단일 테스트
cd modern/api && ./gradlew build                                         # 테스트 포함 전체 빌드
docker compose --profile modern up -d && cd modern/api && ./gradlew bootRun # 로컬 실행 (MariaDB 필요, 포트 8080)
```

### modern/web (React 18 · TypeScript · Vite)

```bash
cd modern/web && npm ci                                         # 의존성 설치. npm install 은 package-lock.json 을 바꾼다
cd modern/web && npm run dev                                    # http://localhost:5173, /api 는 8080 으로 프록시
cd modern/web && npm run lint && npm run typecheck && npm test  # 변경 후 필수
cd modern/web && npm run build
```

- 작업을 끝냈다고 하기 전에 바꾼 모듈의 위 테스트 명령(웹은 lint · typecheck 포함)을 실행하고 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 쓴다. 하나라도 실패하면 "완료"라고 쓰지 않는다.
- 변경을 만든 세션이 스스로 승인하지 않는다. 리뷰는 새 세션에서 `docs/approval-checklist.md` 형식으로 한다.

## 2. 코딩 컨벤션

### modern/api
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`). 호출 순서는 Controller → Service → Repository.
- 다른 도메인의 데이터는 그 도메인의 Service 를 통해 얻는다. 다른 도메인의 Repository 를 주입하지 않는다.
- 요청 · 응답은 record DTO. OSIV 가 꺼져 있으므로 엔티티 → DTO 변환은 `@Transactional` 서비스 메서드 안에서 한다.
- 예외 → HTTP 변환은 `common/GlobalExceptionHandler` 한 곳에서만 한다. 없는 리소스 404(`NotFoundException`), 검증 실패 400, 그 밖 500. 컨트롤러 메서드 안에 try-catch 를 두지 않는다.
- 외부에 노출하는 문항은 `status = 'A'`(`ItemStatus.ACTIVE`)만 조회한다.
- 현재 시각은 주입받은 `Clock` 빈(`common/ClockConfig`)으로 얻는다. `LocalDateTime.now()` 처럼 인자 없는 `now()` 를 호출하지 않는다.
- 로그 레벨: 정상 흐름 `INFO` 이하, 복구 가능한 실패 `WARN`, 요청을 실패시키는 예외 `ERROR`.
- 새 엔드포인트 · 새 public 서비스 메서드마다 테스트 1개 이상: 컨트롤러 `@WebMvcTest`, 서비스 Mockito, 쿼리 `@DataJpaTest`(H2). 테스트 메서드명은 camelCase, `@DisplayName` 은 한국어.
- 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 매직 넘버는 이름 붙인 상수로 뺀다.
- 들여쓰기 4칸, 한 줄 120자 이내, 와일드카드 import 금지.

### modern/web
- 구조는 `templates/CLAUDE.react.md` 가 아니라 실제 코드를 따른다: 컴포넌트 `src/components/<이름>.tsx` 평면 구조, 데이터 훅 `src/hooks/`, API 함수 `src/api/`.
- 함수 컴포넌트만 쓴다. 클래스 컴포넌트 · `React.FC` · `defaultProps` 를 쓰지 않는다.
- HTTP 요청은 `src/api/client.ts` 의 `getJson` 을 거친다. `fetch` 는 `client.ts` 밖에서 호출하지 않는다. 오류는 `ApiError` 로 받는다.
- 응답 타입은 `src/api/types.ts` 에 두고 백엔드 JSON 필드명을 그대로 쓴다.
- 타입 import 는 `import type`. 안 쓰는 변수 · 인자는 `_` 접두사.
- 이벤트 핸들러 prop 은 `on<동작>`, 구현 함수는 `handle<동작>`. 훅은 `use` 접두사.
- 테스트의 fetch 목은 `src/test/mockFetch.ts`, 픽스처는 `src/test/fixtures.ts` 를 쓴다(msw 없음). 테스트에서 실제 `localhost:8080` 을 호출하지 않는다.
- 조회는 `getByRole` · `getByLabelText` 우선, `getByTestId` 는 둘 다 불가능할 때만. 스냅샷 테스트를 만들지 않는다.
- `eslint-disable` 주석에는 사유를 같은 줄에 적는다. Prettier 는 없다.

## 3. 팀 규칙

1. 컨트롤러에서 Repository 를 직접 호출하지 않는다. 컨트롤러 클래스에 `*Repository` 타입 필드 · 생성자 인자 · SQL 문자열이 없어야 한다.
2. 예외를 삼키지 않는다. 빈 `catch` 블록이 없어야 하고, `catch` 는 로그를 남기고 다시 던지거나 도메인 예외로 바꿔 던진다. 로그는 SLF4J(`org.slf4j.Logger`)로만 남긴다.
3. 의존성 추가(`build.gradle` · `package.json`)와 DB 스키마 · 시드 변경(`db/*/init/`)은 먼저 사람에게 묻는다. 물을 때 이유와 대안을 함께 적는다.

## 4. 금지 사항

### 코드
- api: `System.out` · `System.err` · `e.printStackTrace()`, 컨트롤러 밖으로 엔티티 반환, `@Transactional` 메서드 안의 외부 HTTP 호출.
- api: 커넥션 풀이 max 5 · timeout 3s 다. 루프 안에서 Repository 를 호출하는 코드(N+1)를 만들지 않는다.
- web: `any` · `as unknown as` · `@ts-ignore` · `dangerouslySetInnerHTML` · `console.log`. 타입을 못 맞추면 이유를 답변에 쓰고 묻는다.
- 로그에 학생 식별자(`STU-…`) · 이메일 · 토큰을 찍지 않는다.
- 비밀값(운영 계정 · 토큰 · 키)을 코드 · 설정에 리터럴로 넣지 않는다(실습용 더미 `app-pass` 는 예외). `.env*` 파일을 읽거나 만들지 않는다.

### 파일 · 설정
- `modern/api` 의 `application.yml` 은 먼저 묻고 바꾼다. `package-lock.json` 을 손으로 고치거나 지우지 않는다.
- `legacy/item-bank-php/vendor/` 는 수정 · 인용하지 않는다. grep 할 때 `--exclude-dir=vendor` 를 붙인다.
- `README.md` 의 "이 저장소에 넣지 않은 것" 표에 있는 파일(`hooks/block-dangerous.mjs`, `hooks/secret-scan.mjs`, `.mcp.json`, `/api/items/search`, `characterization/tests/<모듈>.test.js` 등)은 요청받기 전에 만들지 않는다.
- 요청에 없는 파일을 바꿨다면 파일마다 이유를 답변에 적는다.
- `characterization/` 스냅샷을 손으로 고치지 않는다. 흔들리는 값은 `lib/normalize.mjs` 에서 정규화하고, 스냅샷을 깨뜨리는 변경은 먼저 사람에게 알린다. 대상 URL 은 `lib/target.mjs` 에만 둔다.
- `mcp-skeleton/` 에서 stdout 에 로그를 쓰지 않는다(`console.error` 만). SDK 는 v2 `@modelcontextprotocol/server` 만 쓴다.

### 실행 환경 · Git
- `docker compose` 는 `up` · `down` · `build` · `logs` 모두 `--profile <이름>` 을 붙인다.
- DB 를 직접 조회할 때는 `readonly` 계정만 쓴다(`app` · `sa` 는 애플리케이션용).
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정을 만들지 않는다.
- `upstream`(`tristan-kkim/mirae-n-handson`)에 push 하지 않는다. `origin` 이 내 포크다.
- 커밋 메시지는 한국어, "무엇을 — 왜" 형식(예: `의존성 설치를 npm ci 로 — npm 버전 차이로 package-lock.json 이 바뀌지 않게`).

## 5. 아키텍처 안내

```
modern/api/src/main/java/com/example/
├── item/        문항 · 단원 · 태그 (Item*, Unit*, Tag*, ItemStatus)
├── assignment/  과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*, ClassRoom*, Submission*)
├── common/      GlobalExceptionHandler, NotFoundException, ErrorResponse, ClockConfig
└── config/      WebConfig (CORS)
modern/api/src/main/resources/application.yml       로컬 MariaDB, 커넥션 풀 · open-in-view: false
modern/api/src/test/resources/application-test.yml  H2 (MariaDB 모드)

modern/web/src/
├── api/         client.ts(getJson · ApiError) · items.ts(API 함수) · types.ts(응답 타입)
├── hooks/       useApiQuery 기반 데이터 훅 (useUnits, useUnitItems, useItem)
├── components/  *.tsx 와 *.test.tsx 를 같은 폴더에 평면으로
└── test/        mockFetch.ts · fixtures.ts
```

- 엔드포인트: `GET /api/units`, `GET /api/units/item-counts`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`. 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.
- 웹 데이터 흐름: 컴포넌트 → `hooks/` → `api/items.ts` → `api/client.ts` → `modern/api`.
- `legacy/assignment-thymeleaf` 는 `AppClock` 이 시각을 `2026-09-15 00:00:00` 으로 고정한다(`-Dapp.clock=system` 로 해제).
- 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 적고, 근거가 없으면 "확인 필요"라고 쓴다.
