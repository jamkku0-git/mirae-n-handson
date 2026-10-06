---
name: convention-check
description: 변경된 코드(또는 지정한 경로)가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지키는지 예 / 아니오로 판정하고 위반을 파일:줄번호로 보고한다. 코드는 고치지 않는다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 있는지 봐 줘" 같은 요청에 사용한다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 코딩 컨벤션 점검

- 코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상

- 인자(`$ARGUMENTS`)로 경로를 받으면 그 경로(디렉터리면 그 아래 파일 전부)만 본다.
- 인자가 없으면 `git status --porcelain` 과 `git diff --name-only HEAD` 에 잡힌 파일을 모은다. `??`(아직 add 하지 않은 새 파일)도 포함하고, 삭제된 파일은 뺀다.
- `modern/api/**/*.java` 와 `modern/web/src/**/*.{ts,tsx}` 만 점검한다. `legacy/` · `vendor/` · `node_modules/` · 그 밖 파일은 "대상 아님"으로 목록에만 적는다.

## 2. 점검 항목

| # | 대상 | 규칙 (어기면 "아니오") |
|---|---|---|
| 1 | api | `System.out` · `System.err` · `printStackTrace()` 가 없다 |
| 2 | api | 인자 없는 `now()`(`LocalDateTime.now()` 등)를 호출하지 않는다 — 주입받은 `Clock` 사용 |
| 3 | api | `*Controller` 클래스에 `*Repository` 필드 · 생성자 인자, SQL 문자열, `try-catch` 가 없다 |
| 4 | api | 빈 `catch` 가 없고, `catch` 는 SLF4J 로 로그를 남긴 뒤 다시 던지거나 도메인 예외로 바꿔 던진다 |
| 5 | api | 와일드카드 import(`import x.*;`)가 없고, 한 줄이 120자 이내다 |
| 6 | web | `any` · `as unknown as` · `@ts-ignore` · `dangerouslySetInnerHTML` · `console.log` 가 없다 |
| 7 | web | `fetch(` 호출이 `src/api/client.ts` 밖에 없다 |
| 8 | web | `React.FC` · 클래스 컴포넌트 · `defaultProps` · `toMatchSnapshot` 이 없고, `eslint-disable` 주석은 같은 줄에 사유가 있다 |

- 새 파일은 전체를, 기존 파일은 `git diff -U0 HEAD -- <파일>` 에 나온 추가 · 변경 줄만 판정한다(인자로 경로를 받았으면 파일 전체).
- Grep 으로 후보를 찾은 뒤 반드시 Read 로 해당 줄을 열어 주석 · 문자열 안의 오탐인지 확인한다.
- 줄 길이(#5)는 Read 결과로 센다. 여러 줄에 걸친 `catch`(#4)는 블록 끝까지 읽고 판정한다.

## 3. 출력 형식 (순서 고정)

**판정 요약** — 점검한 파일 수와 항목별 결과를 한 줄씩:

```
점검 파일 3개 (대상 아님 1개: README.md)
#1 예 · #2 아니오(1) · #3 예 · #4 예 · #5 아니오(2) · #6 해당 없음 · #7 해당 없음 · #8 해당 없음
```

- 해당 모듈 파일이 없으면 "해당 없음", 판정 근거가 부족하면 "확인 필요".

**위반 목록** — 위반이 없으면 "위반 없음" 한 줄만 쓴다.

| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/src/main/java/com/example/item/UnitService.java:42` | #2 인자 없는 `now()` | `Clock` 빈을 주입받아 `LocalDateTime.now(clock)` |

- "확인 필요" 항목은 표 아래에 목록으로 따로 적고, 무엇을 더 보면 판정할 수 있는지 한 줄 덧붙인다.
