# RCA — a-connection-pool

대상 로그: `incident-logs/a-connection-pool/` (더미 데이터). 원본 로그는 고치지 않았다.

## 요약

- **무슨 일**: 2026-09-16 **14:40:52 ~ 14:51:47 KST(약 11분)** 동안 item-bank API 요청이 500 · 502 · 504 로 실패했다(5xx 186건). 14:37:52 부터 3분 동안 전조가 있었다.
- **영향**: 학생 화면의 단원 · 문항 조회(`/api/units` · `/api/items`)와 교사 화면의 학급 리포트가 함께 실패했다.
- **원인**: 학급 리포트 생성(`ReportService.buildClassReport`)이 DB 조회를 마친 뒤에도 트랜잭션 안에서 SHA-256 20만 회 루프를 돌았다. 그동안 커넥션을 15 ~ 21초씩 쥐었다. 리포트 요청이 분당 2건 이하에서 10 ~ 19건으로 늘자 풀 5개가 리포트에 모두 잡혔고, 다른 API 는 3초를 기다리다 실패했다.
- **확신 수준: 높음**. 로그 · 코드 · 설정이 같은 메커니즘을 가리킨다. 다만 장애 전에는 같은 코드가 10초 안에 끝났는데 왜 길어졌는지는 지표가 없어 확인하지 못했다(9절).

## 1. 수집 범위

이 절은 분석할 시간 구간만 정한다. 원인은 아직 해석하지 않는다.
파일을 통째로 읽지 않았고, `wc -l` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 로 필요한 부분만 집계했다.

### 1.1 파일별 기록 기간 · 형식 · 타임존

| 파일 | 줄 수 | 기록 기간(원본 표기) | 기록 기간(KST) | 시각 형식 | 타임존 |
|---|---:|---|---|---|---|
| `app.log` | 5,371 (타임스탬프 있는 줄 1,927 · 스택트레이스 등 연속 줄 3,444) | `2026-09-16T06:43:56.753+09:00` ~ `2026-09-16T15:29:51.760+09:00` | 09-16 06:43:56 ~ 15:29:51 | ISO-8601, 밀리초, 오프셋 포함 | `+09:00` (1,927줄 전부) |
| `nginx-access.log` | 21,086 | `[16/Sep/2026:06:43:56 +0900]` ~ `[16/Sep/2026:15:29:59 +0900]` | 09-16 06:43:56 ~ 15:29:59 | nginx combined `[dd/Mon/yyyy:HH:MM:SS ±zzzz]`, 초 단위 | `+0900` (21,086줄 전부) |
| `nginx-error.log` | 168 | `2026/09/16 09:12:03` ~ `2026/09/16 14:51:47` | 09-16 09:12:03 ~ 14:51:47 | `yyyy/MM/dd HH:MM:SS`, 초 단위 | 표기 없음 → KST 로 판단(아래 근거) |
| `mariadb-slow.log` | 21 (항목 3건) | `# Time: 260916  0:14:09` ~ `# Time: 260916  5:37:52` | 09-16 09:14:09 ~ 14:37:52 | `# Time: yyMMdd H:MM:SS`(질의 종료 시각) + `SET timestamp=<epoch>`(질의 시작 시각) | 표기 없음 → UTC (아래 근거) |

**KST 변환 규칙**

- `app.log`, `nginx-access.log`: 오프셋이 이미 +09:00 이므로 그대로 쓴다.
- `nginx-error.log`: 그대로 쓴다(KST). 근거: `upstream timed out` 줄의 분당 건수(14:41 3건 … 14:51 2건, 합계 55)가 `nginx-access.log` 의 504 분당 건수와 11개 분 모두 정확히 같다.
- `mariadb-slow.log`: `# Time` 값에 **+9시간**을 더한다. 근거: `SET timestamp` epoch 를 변환하면 `1789517647` = 00:14:07 UTC = 09:14:07 KST, `1789537062` = 05:37:42 UTC = 14:37:42 KST 로, `# Time` 보다 질의 시간(Query_time)만큼 앞선 UTC 시각과 맞는다. epoch 를 `TZ=Asia/Seoul date -d @<epoch>` 로 바꾸는 쪽이 가장 확실하다.
- 슬로 로그 3건의 KST 시각(질의 시작 → 종료): 09:14:07 → 09:14:09 (2.3초), 11:02:40 → 11:02:42 (2.0초), 14:37:42 → 14:37:52 (9.8초).

### 1.2 이상 신호 분 단위 집계

집계 기준

| 파일 | 센 줄 |
|---|---|
| `app.log` | 타임스탬프가 있고 레벨이 `WARN` · `ERROR` 인 줄. 타임아웃은 그중 `timed out`/`timeout` 을 포함한 줄 |
| `nginx-access.log` | 상태 코드 5xx (500 · 502 · 504) |
| `nginx-error.log` | `[error]` · `[warn]` 줄. 타임아웃은 `upstream timed out` |
| `mariadb-slow.log` | 오류 레벨이 없으므로 집계하지 않고 위 3건의 시각만 표시한다 |

**평소 수준(06:43 ~ 14:36)**

| 파일 | 평소 |
|---|---|
| `app.log` | 대부분의 분이 0건. `WARN` 은 `state conflict: 마감된 과제는 재배포할 수 없습니다`(409 업무 예외)만 12건 나왔다. 시간당 1~4건이고, 1분에 2건 이상 나온 분은 없다. `ERROR` · 타임아웃 0건 |
| `nginx-access.log` | 5xx 0건(14:40 전까지 하나도 없다). 전체 요청은 시간대별 평균 분당 28~46건(07시 이후) |
| `nginx-error.log` | `[warn] an upstream response is buffered to a temporary file` 2건(09:12, 13:05)만 있다. `[error]` 0건 |

**14:30 ~ 14:59 분당 건수 (KST)**

| 분 | app WARN | app ERROR | app 타임아웃 | access 500 | access 502 | access 504 | access 5xx 계 | error.log error+warn | 그중 timed out | access 전체 요청 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 14:33 | 1 | | | | | | | | | 52 |
| 14:37 | 1 | | | | | | | | | 50 |
| 14:38 | 2 | | | | | | | | | 41 |
| 14:39 | 3 | | | | | | | | | 42 |
| 14:40 | 15 | 2 | 1 | 1 | | | 1 | | | 42 |
| 14:41 | 19 | 12 | 6 | 6 | | 3 | 9 | 3 | 3 | 42 |
| 14:42 | 13 | 4 | 2 | 2 | 13 | 5 | 20 | 20 | 5 | 41 |
| 14:43 | 24 | 20 | 10 | 10 | 2 | 5 | 17 | 8 | 5 | 58 |
| 14:44 | 13 | 4 | 2 | 2 | 5 | 6 | 13 | 15 | 6 | 34 |
| 14:45 | 17 | 16 | 8 | 8 | 14 | 5 | 27 | 22 | 5 | 39 |
| 14:46 | 16 | 8 | 4 | 4 | 16 | 6 | 26 | 25 | 6 | 51 |
| 14:47 | 14 | 10 | 5 | 5 | 6 | 3 | 14 | 10 | 3 | 38 |
| 14:48 | 14 | 8 | 4 | 4 | 6 | 5 | 15 | 15 | 5 | 42 |
| 14:49 | 19 | 14 | 7 | 7 | 8 | 8 | 23 | 20 | 8 | 42 |
| 14:50 | 12 | | | | 11 | 7 | 18 | 22 | 7 | 34 |
| 14:51 | 4 | | | | 1 | 2 | 3 | 3 | 2 | 46 |
| 14:52 | 1 | | | | | | | | | 47 |
| 14:53 ~ 14:59 | | | | | | | | | | 35~47 |

- 빈칸은 0건이다. 14:33 · 14:52 의 WARN 은 평소에도 나오는 409 `state conflict` 다. 14:30 ~ 14:36 에서 이 표에 없는 분은 모든 열이 0이다.
- 이상 구간의 줄 종류: `app.log` 는 `ProxyLeakTask Connection leak detection triggered`(WARN 134), `SqlExceptionHelper`(WARN 49 · ERROR 49, `Connection is not available, request timed out after 3000ms`), `GlobalExceptionHandler unhandled exception`(ERROR 49). `nginx-error.log` 는 `no live upstreams` 68, `upstream timed out` 55, `upstream server temporarily disabled` 26, `connect() failed (111: Connection refused)` 14.
- 파일 사이 정합성: app `unhandled exception` 49건 = access 500 49건, error.log `upstream timed out` 55건 = access 504 55건(분 단위까지 같다).
- 전체 요청 수는 이상 구간에도 분당 34~58건으로 평소 범위 안에 있다. 트래픽이 급증하지는 않았다.

**구간 제안 (KST, 2026-09-16)**

| 경계 | 시각 | 근거 |
|---|---|---|
| 전조 시작 | **14:37:52** | `app.log:1243` 첫 `Connection leak detection triggered`. 이 종류의 WARN 은 이 줄 전에는 한 번도 없다 |
| 영향 시작 | **14:40:52** | `nginx-access.log:18918` 첫 5xx(500), 같은 초에 `app.log:1522` 첫 ERROR |
| 영향 끝 | **14:51:47** | `nginx-access.log:19381` 마지막 5xx(504), `nginx-error.log:168` 마지막 `[error]` |
| 이상 신호 끝 | **14:51:37** | `app.log:5251` 마지막 leak WARN. 14:52 부터 app 줄 수가 분당 1~3줄로 평소 수준으로 돌아온다 |

→ **이상 구간: 14:37:52 ~ 14:51:47 KST** (전조 3분 + 사용자 영향 약 11분).
`app.log` 의 ERROR 는 14:49:51(`app.log:4981`)에서 끝나지만 502 · 504 는 14:51 까지 이어진다. 앱 ERROR 만 보고 끝 시각을 잡으면 2분이 빠진다.

### 1.3 앞뒤 여유

| 방향 | 제안 | 수집 범위(KST) | 이유 |
|---|---|---|---|
| 앞 | **30분** | 14:07:00 부터 | leak 경고는 커넥션을 빌려 간 뒤 임계 시간이 지나야 찍힌다. 그래서 첫 경고(14:37:52)보다 앞서 일어난 일을 볼 수 있어야 한다. 운영 설정의 `leakDetectionThreshold` 값은 이 로그로 알 수 없다(확인 필요). 같은 이유로 슬로 로그 14:37:42(시작) ~ 14:37:52(종료) 항목도 이 여유 안에 들어온다. 이 구간의 평소 409 WARN(14:11, 14:14, 14:33)은 기준선 비교용으로 함께 둔다 |
| 뒤 | **15분** | 15:07:00 까지 | 마지막 5xx(14:51:47) 뒤로 재발 없이 평소 수준이 이어지는지 확인하는 데 필요하다. 로그는 15:29 까지 있으므로 충분히 담긴다 |

→ **수집 범위: 2026-09-16 14:07:00 ~ 15:07:00 KST** (`mariadb-slow.log` 는 UTC 05:07:00 ~ 06:07:00).
슬로 로그는 전체가 3건뿐이라 범위 밖 항목(09:14, 11:02 KST)도 평소와 비교하는 자료로 같이 둔다.

## 2. 타임라인

**대상:** 2026-09-16 14:07:00 ~ 15:07:00 KST. 1.3절의 수집 범위이고 앞뒤 여유를 포함한다. 폴더 안 4개 파일을 KST 한 시간축으로 합쳤다.

**작성 규칙**
- `mariadb-slow.log` 는 UTC 이므로 +9시간 해서 적었다. `nginx-error.log` 는 KST 로 보았다(1.1절 근거).
- 같은 메시지가 반복되면 "첫 발생 시각, 이후 N건/분"으로 묶었다.
- 다른 파일끼리 시각이 같은 초이거나 몇 초 차이라 선후를 단정할 수 없는 곳은 **(선후 불확실)** 로 표시했다.
- ★ 는 가장 많이 나온 오류(nginx `no live upstreams` 68건, 첫 발생 14:42:04)와 첫 실패(14:40:52)보다 앞서면서 평소와 다른 줄이다.
- 로그에 없는 사건은 쓰지 않았고, 원인 해석도 넣지 않았다.

**참고**
- 출처 약어: `app` = `app.log`, `access` = `nginx-access.log`, `error` = `nginx-error.log`, `slow` = `mariadb-slow.log`.
- access 로그 시각은 응답이 끝난 시각이다. slow 로그 `# Time` 은 질의 종료 시각, `SET timestamp` 는 질의 시작 시각이다.
- 학생 식별자는 등장 순서대로 학생A, 학생B … 별칭으로 바꿨다.
- 설정 재적재 흔적(`reload` · `refresh` · `reconfigur` · `signal` · `restart` · `shutdown` · `Started`)은 세 파일 모두 0건이다.

| 시각 (KST) | 출처 (파일:줄번호) | 사건 요약 | 원문 발췌 (80자 이내) |
|---|---|---|---|
| 14:10:05 | `app:1148` | 학급 리포트 생성. 이후 14:18:13(`app:1175`)까지 5건(분당 0~1건) | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38 | `access:17605`, `app:1154` | 재배포 요청 409. 14:14:03(`access:17722`, `app:1165`)에 같은 요청 반복 | `POST /api/distributions/3/redistribute HTTP/1.1" 409` |
| 14:19:38 | `access:17977`, `app:1182` | 재배포 성공(distribution 7) | `redistributed distribution 7 (assignment 5, class 3) reason=학생A 결석으로 재배포` |
| 14:18:14 ~ 14:37:59 | `access` | 리포트 요청 0건. 전체 요청은 분당 34~52건 | — |
| 14:33:45 | `access:18610`, `app:1229` | 재배포 요청 409 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=2` |
| 14:37:42 | `slow:20` | ★ 느린 쿼리 시작(UTC 05:37:42) | `SET timestamp=1789537062;` |
| 14:37:52 | `slow:15` ~ `slow:21` | ★ 느린 쿼리 종료. 9.81초, 128만 행 조회 후 4행 반환. 대상은 submission, distribution_id=5. 이 날 슬로 로그 3건 중 가장 길다(나머지 2.0 · 2.3초) | `# Query_time: 9.812417 … Rows_sent: 4  Rows_examined: 1284310` |
| 14:37:52 | `app:1243` | ★ 첫 커넥션 leak 경고(이 날 처음 나오는 종류). 이후 1(14:37) → 2 → 3 → 14 → 13 → 10 → 14 → 11 → 9 → 12 → 9 → 10 → 12 → 11 → 3(14:51)건/분, 합계 134건. 스택의 앱 프레임은 모두 `ReportController.java:22`. **(선후 불확실: 느린 쿼리 종료와 같은 초)** | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:38:01 | `access:18801`, `app:1258`, `app:1259` | ★ 리포트 요청 재개(교사단말A, class 3, 200). 같은 초에 leak 커넥션 반납 INFO. 반납 INFO 는 이후 3 → 3 → 10 → 15 → 10 → 15 → 11 → 8 → 13 → 9 → 10 → 13 → 9 → 5(14:51)건/분. **(선후 불확실: access 응답, 리포트 생성, 반납 INFO 가 같은 초)** | `Previously reported leaked connection org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:38:27 | `access:18815` | ★ 같은 client 가 같은 리포트를 다시 요청. 이후 14:38:57(`access:18839`), 14:39:24(`access:18862`)로 약 30초 간격 | `GET /api/classes/3/report HTTP/1.1" 200` |
| 14:38:29 | `access:18817`, `app:1278` | ★ 재배포 성공(distribution 5, 느린 쿼리 대상과 같은 ID). **(선후 불확실: app 와 access 가 같은 초)** | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:38 ~ 14:40 | `access` | ★ 트래픽 변화: 리포트 요청이 분당 3 → 3 → 10건. 14:18 이전에는 분당 0~1건 | — |
| 14:40 | `app` | ★ leak 경고 대상 커넥션이 2개(14:38) → 3개(14:39) → 5개(14:40)로 늘어남 | `…Connection@3f6c2a91 …`, `…Connection@5b0e9e0c …` |
| 14:40:43 | `access:18912` | ★ client 교사단말B 의 첫 리포트 요청. 이 client 는 06:44 부터 요청이 있었지만 리포트는 이때가 처음 | `교사단말B … "GET /api/classes/1/report HTTP/1.1" 200` |
| 14:40:52 | `app:1522`, `app:1523`, `access:18918` | 첫 커넥션 풀 타임아웃 → 첫 앱 ERROR → 첫 500. 이후 1(14:40) → 6 → 2 → 10 → 2 → 8 → 4 → 5 → 4 → 7(14:49)건/분, 합계 49건. 경로는 `/api/units/{code}/items` 19, `/api/units` 14, `/api/items/{id}` 7, `/api/classes/{id}/report` 5, `/api/distributions/{id}` 4. **(선후 불확실: app ERROR 와 access 500 이 같은 초)** | `request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 14:40 ~ 14:51 | `access` | 리포트 요청 분당 10 → 16 → 15 → 19 → 14 → 15 → 19 → 12 → 14 → 16 → 10 → 5건. 전체 요청은 분당 34~58건 | — |
| 14:41:06 | `error:6`, `access:18933` | 첫 upstream 타임아웃 → 첫 504. 이후 3(14:41) → 5 → 5 → 6 → 5 → 6 → 3 → 5 → 8 → 7 → 2(14:51)건/분, 합계 55건. **(선후 불확실: error 와 access 가 같은 초)** | `upstream timed out (110: Connection timed out) while reading response header` |
| 14:42:01 | `error:10`, `error:11`, `access:18970` | 첫 upstream 비활성 · 첫 연결 거부 · 첫 502. 비활성은 2(14:42) → 1 → 4 → 3 → 3 → 1 → 4 → 4 → 4(14:50)건/분, 합계 26건. 연결 거부는 14:42 ~ 14:50 에 분당 1~2건, 합계 14건. 502 는 13(14:42) → 2 → 5 → 14 → 16 → 6 → 6 → 8 → 11 → 1(14:51)건/분, 합계 82건. **(선후 불확실: error 2줄과 access 502 가 같은 초)** | `upstream server temporarily disabled while reading response header from upstream` |
| 14:42:04 | `error:12` | 첫 `no live upstreams`(이 구간에서 가장 많은 오류). 이후 11(14:42) → 1 → 4 → 12 → 14 → 5 → 5 → 6 → 9 → 1(14:51)건/분, 합계 68건 | `no live upstreams while connecting to upstream` |
| 14:42:42 | `access:18998` | 재배포 요청 409 | `POST /api/distributions/1/redistribute HTTP/1.1" 409` |
| 14:44:19 | `access:19078` | 재배포 성공(distribution 8) | `POST /api/distributions/8/redistribute HTTP/1.1" 200` |
| 14:49:51 | `app:4980`, `app:4981` | 마지막 커넥션 풀 타임아웃 · 마지막 앱 ERROR | `unhandled exception on /api/units/M5-3/items` |
| 14:50:22 | `access:19321` | 재배포 요청 409 | `POST /api/distributions/3/redistribute HTTP/1.1" 409` |
| 14:50:52 | `error:158`, `error:159` | 마지막 upstream 비활성 · 마지막 연결 거부 | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:51:02 | `error:166` | 마지막 `no live upstreams` | `no live upstreams while connecting to upstream` |
| 14:51:37 | `app:5251` | 마지막 leak 경고 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:51:47 | `error:168`, `access:19381` | 마지막 upstream 타임아웃 · 마지막 504(마지막 5xx). **(선후 불확실: error 와 access 가 같은 초)** | `"GET /api/classes/3/report HTTP/1.1" 504` |
| 14:51:52 | `access:19386`, `app:5268` | 재배포 요청 409. 14:52:09(`access:19401`, `app:5269`)에 1건 더 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=2` |
| 14:52 ~ 15:06 | `app`, `access`, `error` | 5xx · 앱 ERROR · leak 경고 · nginx `[error]`/`[warn]` 모두 0건. 앱 줄 수는 분당 1~3줄, 전체 요청은 분당 35~47건 | — |
| 14:59:15 | `app:5283` | 14:52 이후 첫 리포트 생성. 14:59 ~ 15:04 에 리포트 요청 4건(분당 0~2건) | `built class report for class 3: 3 distributions, 10 submissions` |

## 3. 가설과 검증

이 절은 가설과 확인 방법, 그리고 로그로 확인할 수 있는 항목을 실행한 결과를 적는다. 판정은 반증 조건에 비춘 "유지 / 기각 / 판단 불가(로그 부족)"이며, 근본 원인 판정은 3.2절에 적었다. 실행한 명령과 출력은 3.1절에 그대로 남겼다.
지지 근거의 줄번호는 이 문서(`docs/rca/a-connection-pool.md`)의 타임라인 · 집계 줄이다. 원본 로그 줄은 괄호 안에 적었다.

**증상으로 두는 것(원인이라고 부르지 않는다)**
- `no live upstreams` 68건(최다, `a-connection-pool.md:132`), `upstream timed out` 55건, `Connection is not available, request timed out after 3000ms` 49건(`a-connection-pool.md:128`), `Connection leak detection triggered` 134건(`a-connection-pool.md:121`).
- 이 메시지들은 "무엇이 일어났는가"이고, 아래 가설은 "왜 일어났는가"에 대한 후보다.

**가설 표**

| # | 계층 | 1. 가설 | 2. 지지 근거 | 3. 반증 조건 | 4. 확인 방법 | 5. 실행 결과(숫자) | 6. 판정 |
|---|---|---|---|---|---|---|---|
| H1 | 애플리케이션 코드 | 학급 리포트 생성이 DB 조회를 끝낸 뒤에도 트랜잭션 안에서 오래 걸리는 작업을 해 커넥션을 오래 쥐고, 리포트 요청이 몰리자 풀 5개가 모두 리포트 요청에 잡혀 다른 API 가 3초 대기 끝에 실패했다 | `a-connection-pool.md:121` leak 경고 스택의 앱 프레임이 모두 `ReportController.java:22` · `a-connection-pool.md:122` 경고 뒤 같은 커넥션이 반납됨(진짜 누수가 아니라 장시간 점유) · `a-connection-pool.md:126` 경고 대상 커넥션 2 → 3 → 5개 · `a-connection-pool.md:128` `total=5, active=5, idle=0, waiting=4`, 실패 경로 대부분이 리포트가 아닌 `/api/units` · `/api/items` | (a) leak 경고 스택에 리포트 외 경로가 적지 않게 섞여 있다. (b) 커넥션을 쥔 시간의 대부분이 SQL 실행 시간이다(→ H2 쪽). (c) 코드에서 `@Transactional` 범위 안에 DB 와 무관한 긴 작업이 없다. (d) leak 경고 뒤 반납되지 않은 커넥션이 있다(그러면 장시간 점유가 아니라 실제 누수) | `grep -A12 'leak detection triggered' incident-logs/a-connection-pool/app.log \| grep -o 'com\.example\.[A-Za-z.]*([A-Za-z]*\.java:[0-9]*)' \| sort \| uniq -c` 로 경로 분포 확인 · `grep -c 'was returned to the pool (unleaked)' …/app.log` 와 경고 수(134) 비교 · 같은 `Connection@…` 의 경고 → 반납 시각 차를 `awk` 로 계산 · `modern/api/src/main/java/com/example/assignment/ReportService.java:41-71` 에서 트랜잭션 경계와 `sign()` 호출 위치, `SIGNATURE_ROUNDS` 확인 | 리포트 경로 134/134(leak 경고 134건 전부 `ReportController.java:22`) · 반납 134/134 · 경고→반납 5.538 ~ 11.508초(중앙값 8.929) · 장애 구간 슬로 로그 0건(H2 참조) · 장애 전 리포트 50건은 leak 경고 0건 | **유지** — 반증 (a)(b)(d) 해당 없음(경로 100% 리포트, 누수 아닌 장시간 점유, 점유 시간(임계 + 5.5 ~ 11.5초) 동안 2.04초 넘는 쿼리 0건). (c)는 코드 확인 사항으로 `ReportService.java:71` 의 `sign()` 이 `@Transactional` 안에 있어 해당 없음. 단, 같은 코드로 장애 전 50건은 임계를 넘지 않았으므로 H1 만으로는 "왜 14:37 부터인가"를 설명하지 못한다 |
| H2 | DB · 쿼리 | `submission` 을 `distribution_id` 로 읽는 쿼리가 인덱스를 타지 못하고 대량 스캔으로 느려져(9.8초) 커넥션 점유 시간이 길어졌고, 그 결과 풀이 고갈됐다 | `a-connection-pool.md:119` · `a-connection-pool.md:120` 9.81초, `Rows_examined: 1284310` 에 `Rows_sent: 4`, 이 날 슬로 로그 중 가장 길다(`mariadb-slow.log:15-21`) · `a-connection-pool.md:121` 첫 leak 경고와 같은 초(선후 불확실) · `a-connection-pool.md:124` 14:38:29 같은 distribution 5 재배포 | (a) 풀 고갈 구간(14:40 ~ 14:51)에 슬로 로그 항목이 없고, `long_query_time` 이 커넥션 점유 시간(leak 임계 이상)보다 짧게 설정돼 있다 → 그 구간 쿼리는 느리지 않았다. (b) 실행 계획이 `idx_submission_distribution` 를 쓰고 예상 행 수가 작다. (c) 9.8초 쿼리가 leak 경고 커넥션과 다른 연결(`Thread_id`)에서 나왔다 | `grep -n '^# Time' incident-logs/a-connection-pool/mariadb-slow.log` 로 구간 내 항목 수 확인 · 로컬 compose DB 에 **readonly 계정**으로 `SHOW VARIABLES LIKE 'long_query_time';`, `SHOW INDEX FROM submission;`, `EXPLAIN select … from submission where distribution_id=5 order by student_id;` · 스키마 `db/mariadb/init/01-schema.sql:105-114` 의 인덱스 정의 확인. 운영 행 수 · 운영 인덱스 상태는 로컬과 다를 수 있으므로 확인 필요 | 슬로 로그 3건(`mariadb-slow.log:1`, `:8`, `:15`), 장애 구간(UTC 05:40 ~ 05:52) 0건 · 기록된 최소 Query_time 2.041903초 → `long_query_time` ≤ 2.04초로 추정 · 3건 모두 `API-1` 접속 | **기각**(풀 고갈 구간 전체의 원인으로서) — 134건의 장시간 점유 중 2.04초 넘는 쿼리가 하나도 없고, 9.8초 쿼리는 첫 leak 경고(14:37:52) 1건의 계기일 가능성만 남는다. 전제: 슬로 로그가 하루 내내 켜져 있었고 샘플링(`log_slow_rate_limit`)이 없었다. 이 전제는 MariaDB 전역 변수(`long_query_time` · `log_slow_rate_limit`) 기록이 있어야 확정된다 |
| H3 | 트래픽 · 클라이언트 | 교사 화면의 리포트 반복 요청(같은 client 약 30초 간격 + 새 client 합류)이 늘어, 전체 요청량은 평소 수준인데도 비싼 엔드포인트의 비중이 커져 풀 수요가 공급(5개)을 넘었다 | `a-connection-pool.md:117` 14:18 ~ 14:37 리포트 0건 · `a-connection-pool.md:123` 같은 client 가 약 30초 간격으로 같은 리포트 재요청 · `a-connection-pool.md:125` 리포트 분당 3 → 3 → 10건 · `a-connection-pool.md:127` 교사단말B 첫 리포트 요청 · `a-connection-pool.md:129` 분당 10 ~ 19건 · `a-connection-pool.md:71` 전체 요청은 평소 범위 | (a) 평소 시간대에도 리포트 요청이 분당 10건 이상인 분이 있는데 그때 풀 타임아웃이 없었다 → 요청량만으로는 고갈되지 않는다. (b) 늘어난 리포트 요청 대부분이 5xx 를 받은 직후의 재요청이다 → 원인이 아니라 증폭(결과)이다. (c) 반복 요청이 장애 전부터 매일 같은 패턴이다(다른 날 로그 필요) | `awk '/GET \/api\/classes\/[0-9]+\/report/{print substr($4,2,17)}' incident-logs/a-connection-pool/nginx-access.log \| uniq -c \| sort -rn \| head` 로 하루 전체의 분당 최대치 확인 · client(`$1`)별 리포트 요청 시각과 직전 응답 코드를 나란히 뽑아 5xx 직후 재요청 비율 계산 · Referer · User-Agent 로 화면 자동 새로고침인지 사람의 재시도인지 구분 · 다른 날 access 로그는 이 폴더에 없음(확인 필요) | 장애 전(~14:36) 리포트 분당 최대 2건, 장애 구간 분당 최대 19건(14:43 · 14:46) · 시간대별 07~13시 2~11건/시, 14시 179건 · 14:37 ~ 14:51 리포트 171건(client 4곳: 교사단말C 67 · 교사단말A 40 · 교사단말D 35 · 교사단말B 29) · 첫 5xx 전(14:37:00 ~ 14:40:51) 15건 · client 첫 리포트 14:38:01 · 14:39:54 · 14:40:27 · 14:40:43 · 직전 응답이 5xx 인 재요청 84/171(49%) | **유지** — 반증 (a) 해당 없음(평소 분당 최대 2건), (b) 해당 없음(증가가 첫 5xx 전부터 시작, 재요청은 절반만), (c) **판단 불가**: 다른 날 같은 시간대 `nginx-access.log` 가 있어야 매일 같은 패턴인지 알 수 있다 |
| H4 | 인프라(nginx · 업스트림 구성) | 업스트림 2대 중 `API-2` 가 연결을 거부하는 상태여서 요청이 `API-1` 한 대에 몰렸고, nginx 의 실패 판정이 남은 한 대도 일시 비활성화해 앱 오류보다 많은 502 · 504 로 증폭됐다 | `a-connection-pool.md:131` 첫 `Connection refused` · `upstream server temporarily disabled`(원본 `nginx-error.log:11` 의 거부 대상은 `API-2:8080`, 14건 모두 같은 호스트) · `a-connection-pool.md:132` `no live upstreams` 68건 · `a-connection-pool.md:130` 504 55건 · 앱 500 49건 대비 502 82건 + 504 55건(`a-connection-pool.md:70`, `:131`) · `a-connection-pool.md:110` 재시작 흔적 0건(이 앱 로그 기준) | (a) 14:42 이전에도 `API-2` 가 정상 응답한 기록이 있고 장애 구간 내내 요청이 두 대에 고르게 나뉘었다 → "한 대에 몰림"은 틀리다. (b) `API-2` 의 거부가 `API-1` 의 오류보다 뒤에 시작됐다 → 원인이 아니라 같은 원인의 결과다. (c) nginx 의 `max_fails` · `fail_timeout` · `proxy_read_timeout` 이 이번 패턴(타임아웃 뒤 비활성)과 맞지 않는다 | `grep -o 'upstream: "http://[^/"]*' incident-logs/a-connection-pool/nginx-error.log \| sort \| uniq -c` · `grep -n 'API-2' …/nginx-error.log \| head -3` 로 첫 거부 시각 확인 · access 로그 형식에 `$upstream_addr` 가 없어 정상 요청의 분산은 이 로그로 알 수 없음(확인 필요) · `app.log` 가 어느 인스턴스 것인지(`mariadb-slow.log` 의 접속 호스트는 `API-1` 뿐) 확인 필요 · `API-2` 의 앱 로그와 nginx `upstream item_bank_api` 설정은 저장소에 없으므로 운영 담당에게 요청(확인 필요) | `API-2` 14건 전부 `Connection refused`, 첫 14:42:01(`nginx-error.log:11`) · 끝 14:50:52(`:159`) · `API-1` 첫 오류 14:41:06(`:6`), 타임아웃 55 · 비활성 26 · 앱 첫 ERROR 14:40:52 · 09:12 ~ 14:41 에 `Connection refused` 0건 · access 로그는 12 · 15 · 20 필드, `$upstream_addr` 없음 | **기각**(원인으로서) — 반증 (b) 해당: `API-2` 의 거부는 앱 첫 ERROR 보다 69초, `API-1` 첫 타임아웃보다 55초 뒤에 처음 나온다. 앞서는 사건이 아니다. 502 · `no live upstreams` 증폭 요인으로는 남는다. (a) 정상 요청의 분산은 **판단 불가**: access 로그에 `$upstream_addr` 와 nginx `upstream item_bank_api` 설정(`backup` · `max_fails` · `fail_timeout`)이 있어야 한다 |

**메모**
- 네 가설은 서로 배타적이지 않다. 예를 들어 H1 · H2 는 "커넥션을 오래 쥔 이유", H3 는 "동시 수요가 늘어난 이유", H4 는 "앱 오류가 사용자 5xx 로 커진 이유"를 다룬다. 3.1절 실행 결과로 H1 · H3 이 남았다. 둘은 겹친다: 리포트 1건이 트랜잭션 안에서 오래 걸리는 구조(H1)에 동시 리포트 요청이 분당 2건 이하에서 10 ~ 19건으로 늘어난 일(H3)이 겹쳤을 가능성이 있다. 근본 원인 판정은 3.2절에 적었다.
- 운영 `leakDetectionThreshold` · 풀 설정이 `modern/api/src/main/resources/application.yml:13-18`(max 5, timeout 3s, leak 10s)과 같은지는 이 로그로 알 수 없다(확인 필요, `a-connection-pool.md:89`).

### 3.1 실행 기록

모든 명령은 `incident-logs/a-connection-pool/` 에서 실행했다. 출력은 그대로 옮겼다(긴 줄은 앞부분만).
코드 · DB 확인(`ReportService.java` 열람, `EXPLAIN` · `SHOW VARIABLES`)은 로그 확인이 아니므로 여기서 실행하지 않았다. `ReportService.java:41-71` 는 2절 작성 때 읽은 내용을 H1 판정에 썼다.

**H1 — 리포트 경로 · 장시간 점유**

```console
$ grep -A12 'leak detection triggered' app.log | grep -o 'com\.example\.[A-Za-z.]*([A-Za-z]*\.java:[0-9]*)' | sort | uniq -c
    134 com.example.assignment.ReportController.classReport(ReportController.java:22)

$ grep -c 'Connection leak detection triggered' app.log
134
$ grep -c 'was returned to the pool (unleaked)' app.log
134

# 같은 Connection@… + thread 의 경고 → 반납 시각 차(초)
$ awk '/leak detection triggered for/{…t[k]=$1} /Previously reported leaked connection/{…print d}' app.log | sort -n | awk '{…}END{print "n="NR,"min=",…}'
n=134 min=5.538 median=8.929 max=11.508 avg=9.00904

# 장애 전 리포트 생성 수와 leak 경고 수
$ awk '$1<"2026-09-16T14:37:00" && /built class report/' app.log | wc -l
50
$ awk '$1<"2026-09-16T14:37:00" && /leak detection/' app.log | wc -l
0
$ awk '$1>="2026-09-16T14:37:00" && $1<"2026-09-16T14:52:00" && /built class report/' app.log | wc -l
134
```

**H2 — 슬로 쿼리**

```console
$ grep -n '^# Time' mariadb-slow.log
1:# Time: 260916  0:14:09
8:# Time: 260916  2:02:42
15:# Time: 260916  5:37:52
$ grep -n 'Query_time' mariadb-slow.log
4:# Query_time: 2.318446  Lock_time: 0.000071  Rows_sent: 7  Rows_examined: 41822
11:# Query_time: 2.041903  Lock_time: 0.912004  Rows_sent: 5  Rows_examined: 5
18:# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310
$ grep -n 'User@Host' mariadb-slow.log
2:# User@Host: app[app] @  [API-1]
9:# User@Host: app[app] @  [API-1]
16:# User@Host: app[app] @  [API-1]

# 장애 구간 14:40 ~ 14:52 KST = UTC 05:40 ~ 05:52
$ grep -c '^# Time: 260916  5:4[0-9]\|^# Time: 260916  5:5[0-2]' mariadb-slow.log
0
$ grep -ci 'long_query_time' mariadb-slow.log
0
```

**H3 — 리포트 요청량 · client**

```console
$ R='GET /api/classes/[0-9]+/report'
$ awk -v r="$R" '$0~r{print substr($4,2,17)}' nginx-access.log | uniq -c | sort -rn | head -8
     19 16/Sep/2026:14:46
     19 16/Sep/2026:14:43
     16 16/Sep/2026:14:49
     16 16/Sep/2026:14:41
     15 16/Sep/2026:14:45
     15 16/Sep/2026:14:42
     14 16/Sep/2026:14:48
     14 16/Sep/2026:14:44

# 14:37 이전 분당 최대
$ awk -v r="$R" '$0~r && substr($4,14,5)<"14:37"{print substr($4,2,17)}' nginx-access.log | uniq -c | sort -rn | head -3
      2 16/Sep/2026:12:20
      1 16/Sep/2026:14:18
      1 16/Sep/2026:14:17

$ awk -v r="$R" '$0~r{print substr($4,14,2)}' nginx-access.log | sort | uniq -c
      2 07   5 08   2 09  11 10   7 11  11 12   8 13 179 14   4 15

$ awk -v r="$R" '$0~r && substr($4,14,5)>="14:37" && substr($4,14,5)<"14:52"{print $1}' nginx-access.log | sort | uniq -c
     67 교사단말C
     40 교사단말A
     35 교사단말D
     29 교사단말B

# client 별 14:30 이후 첫 리포트 시각
교사단말C 14:39:54
교사단말A 14:38:01
교사단말D 14:40:27
교사단말B 14:40:43

# 첫 5xx(14:40:52) 전 리포트 수
$ awk -v r="$R" '$0~r && substr($4,14,8)>="14:37:00" && substr($4,14,8)<"14:40:52"' nginx-access.log | wc -l
15

# 14:37 ~ 14:51 리포트 요청 중 같은 client 의 직전 응답이 5xx 인 것
$ awk -v r="$R" 'substr($4,14,5)>="14:37" && substr($4,14,5)<"14:52"{ if($0~r){tot++; if(last[$1]~/^5/) after5++} last[$1]=$9 } END{print "리포트",tot,"직전5xx",after5}' nginx-access.log
리포트 171 직전5xx 84
```

**H4 — 업스트림**

```console
$ grep -o 'upstream: "http://[^/"]*' nginx-error.log | sort | uniq -c
     83 upstream: "http://API-1:8080
     14 upstream: "http://API-2:8080
     68 upstream: "http://item_bank_api

# 메시지 종류 × upstream (other 5줄은 09:12 ~ 13:18 의 info · warn 으로 업스트림 장애와 무관)
$ awk '{…m=no_live|refused|disabled|timeout|other…; print m, upstream}' nginx-error.log | sort | uniq -c
     26 disabled //API-1:8080
     68 no_live //item_bank_api
      3 other
      2 other //API-1:8080
     14 refused //API-2:8080
     55 timeout //API-1:8080

$ grep -n 'API-2' nginx-error.log | sed -n '1p;$p' | cut -c1-60
11:2026/09/16 14:42:01 [error] 29#29: *225045 connect() fail
159:2026/09/16 14:50:52 [error] 29#29: *225778 connect() fai
$ grep -n 'API-1' nginx-error.log | grep -v buffered | head -1 | cut -c1-80
6:2026/09/16 14:41:06 [error] 29#29: *224941 upstream timed out (110: Connection

# access 로그 필드 수 — $upstream_addr · $request_time 이 없다
$ awk '{print NF}' nginx-access.log | sort | uniq -c
    795 12
   3512 15
   7380 20
   5769 24
   3630 27
```

**판단 불가 항목과 필요한 로그**

| 가설 · 조건 | 필요한 로그 |
|---|---|
| H1 · 총 점유 시간 | 운영 Hikari `leakDetectionThreshold` 값(경고 → 반납 5.5 ~ 11.5초에 이 값을 더한 것이 실제 점유 시간) |
| H2 · 전제 | MariaDB `long_query_time` · `log_slow_rate_limit` · `slow_query_log` 의 그날 값 |
| H3 · (c) | 다른 날 같은 시간대의 `nginx-access.log`(리포트 요청이 매일 14시대에 몰리는지) |
| H4 · (a) | `$upstream_addr` · `$upstream_response_time` 을 남기는 access 로그, nginx `upstream item_bank_api` 설정, `API-2` 의 앱 로그 |

### 3.2 판정

3.1절에서 유지된 H1 · H3 을 코드 · 설정과 대조했다. 단서는 로그에 찍힌 클래스(`ReportController` · `ReportService` · `ProxyLeakTask`), 풀 이름(`itembank-pool`), 수치(`3000ms`, `total=5`)다.
설정값은 파일에서 읽은 값만 쓴다. 벤치마크 · 테스트는 실행하지 않았다.

**대조한 곳**

| 단서(로그) | 찾은 코드 · 설정 |
|---|---|
| 스택 `ReportController.classReport(ReportController.java:22)` → `ReportService$$SpringCGLIB$$0.buildClassReport` → `TransactionInterceptor` | `modern/api/src/main/java/com/example/assignment/ReportController.java:22`, `ReportService.java:41-71` |
| `itembank-pool - Connection is not available, request timed out after 3000ms (total=5 …)` | `modern/api/src/main/resources/application.yml:10`(`pool-name: itembank-pool`), `:14`(`maximum-pool-size: 5`), `:15`(`connection-timeout: 3000`) |
| `ProxyLeakTask … leak detection triggered` | `application.yml:18`(`leak-detection-threshold: 10000`) |
| 실패 경로 `/api/units` · `/api/units/{code}/items` · `/api/items/{id}` | `UnitService.java:10`, `ItemService.java:14`(클래스 단위 `@Transactional(readOnly = true)`) |
| 리포트 요청 client(Referer `https://lms.example.com/teacher/classes/{id}/report`) | `modern/web/src` 에 리포트 호출 · `setInterval` · 폴링 코드 0건 |

**가설별 대조 결과**

| 가설 | 코드 · 설정 판정 | 근거 (파일:줄번호 인용) |
|---|---|---|
| H1 | **지지한다** | `ReportService.java:41` `@Transactional` → `:42` `public ClassReport buildClassReport(Integer classId)`. DB 조회는 `:52-54` 에서 끝나고, 그 뒤 `:71` `String signature = sign(payload.toString());` 가 같은 트랜잭션 안에서 실행된다. `:85-88` `sign()` 은 `for (int round = 0; round < SIGNATURE_ROUNDS; round++)`, `:23` `static final int SIGNATURE_ROUNDS = 200_000;` 로 DB 와 무관한 CPU 루프다. 코드 주석이 같은 사실을 적고 있다: `:69-70` "TODO: 트랜잭션 밖으로 … DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다." 풀은 `application.yml:14` `maximum-pool-size: 5`, `:15` `connection-timeout: 3000` 이다. 로그의 `total=5` · `3000ms` 와 값이 같다. 리포트가 아닌 경로도 `UnitService.java:10` · `ItemService.java:14` 의 `@Transactional(readOnly = true)` 때문에 같은 풀에서 커넥션을 받아야 해서, 리포트가 풀을 다 쥐면 3초 뒤 실패한다(`a-connection-pool.md:128` 경로 분포와 맞다) |
| H3 | **관련 코드를 찾지 못했다** | 리포트를 반복 호출하는 쪽은 Referer 기준 `lms.example.com` 교사 화면이다. 그 코드는 이 저장소에 없다. `modern/web/src` 에서 `report` · `setInterval` · `refetch` · `poll` 을 grep 한 결과 0건이다. 서버에도 리포트 요청량을 제한 · 캐시하는 설정이 없다(`application.yml` 전체 34줄에 해당 키 없음). 이것은 "제한이 없다"는 사실일 뿐, 요청이 왜 늘었는지에 대한 근거는 아니다 |

**설정값과 로그 숫자를 함께 놓은 계산** (실행이 아니라 산술이다)

- 커넥션 1개 점유 시간 = leak 임계 + 경고→반납 간격. `application.yml:18` 의 10,000ms 와 3.1절의 간격(5.538 ~ 11.508초, 평균 9.009초)을 쓰면 **15.5 ~ 21.5초(평균 약 19.0초)**다.
- 동시에 쥐고 있는 커넥션 수 ≈ 분당 리포트 수 × 점유 시간 / 60. 장애 구간 분당 19건(14:43 · 14:46)이면 19 × 19.0 / 60 ≈ **6.0개** > 풀 5개. 분당 10건이면 약 3.2개다. 같은 점유 시간이라도 장애 전 분당 최대 2건이면 약 0.6개에 그친다.
- 즉 "트랜잭션 안 CPU 루프로 커넥션을 오래 쥔다(H1)"는 구조 위에서는, 관측된 리포트 요청량만으로 풀 5개가 넘친다.
- 단, `application.yml:11` 주석("운영 값과 같게 맞춘다")은 파일에 적힌 주장이다. 운영 실제 값을 로그로 확인하지는 못했다.

**최종 판정**

| 가설 | 결론 | 사유 |
|---|---|---|
| **H1 애플리케이션 코드** | **채택** | 로그(leak 134/134 리포트 경로, 134/134 반납, 풀 타임아웃 `total=5 active=5`)와 코드(`ReportService.java:41`, `:71`, `:85-88`)가 같은 메커니즘을 가리킨다. 설정(`application.yml:14-15`, `:18`)도 로그 수치와 같다 |
| H2 DB · 쿼리 | 기각 | 장애 구간 슬로 쿼리 0건(3.1절). 스키마에 `submission.distribution_id` 인덱스가 있다(`db/mariadb/init/01-schema.sql:112` `KEY idx_submission_distribution (distribution_id)`). 다만 14:37 의 9.8초 · 128만 행 스캔은 이 인덱스와 맞지 않는다. 운영 스키마가 같은지는 별도 확인 필요다(이번 장애의 원인과는 분리) |
| H3 트래픽 | 채택하지 않음(촉발 요인으로 남김) | 기각할 반증은 없다. 하지만 리포트가 아닌 엔드포인트는 요청량이 평소 수준인데도 실패했고, 점유 시간이 짧았다면 분당 19건으로는 풀이 넘치지 않는다(위 계산). 요청 증가는 "언제 터졌는가"를 설명할 뿐 "왜 터졌는가"의 원인은 아니다. 늘어난 이유를 판단할 코드(lms 교사 화면)도 이 저장소에 없다 |
| H4 인프라 | 기각 | `API-2` 의 연결 거부는 앱 첫 ERROR 보다 69초 늦다(3.1절). 502 · `no live upstreams` 를 키운 증폭 요인일 뿐이다. nginx 설정 파일은 저장소에 없어 코드 대조를 하지 못했다 |

**확신 수준: 높음** (H1 의 메커니즘에 대해)
- 높음인 이유: 로그 세 종류(leak 경고 경로, 반납, 풀 타임아웃 수치)와 코드(트랜잭션 범위 안의 20만 회 루프, 그 사실을 적은 TODO), 설정(풀 5, 대기 3초, leak 10초)이 서로 맞는다. 산술로도 관측된 요청량에서 풀이 넘친다.
- 남은 불확실성(이것이 확인되면 확신이 내려간다):
  1. 장애 전 리포트 50건은 같은 코드인데 leak 경고가 0건이었다. 즉 그때는 점유가 10초 미만이었다. 장애 중 15.5초 이상으로 늘어난 이유를 동시 실행 시 CPU 경합으로 추정하지만, CPU · 스레드 지표가 없어 확인하지 못했다.
  2. 운영 Hikari 값이 `application.yml` 과 같다는 근거는 `:11` 주석뿐이다.
  3. 첫 경고(14:37:52)가 9.8초 슬로 쿼리와 같은 초다. 첫 1건의 계기는 H2 쪽일 수 있다.

## 4. 원인

채택한 가설은 H1(3.2절)이다. 인과 순서대로 적고, 문장 번호(C1 …)는 5절의 근거와 대응한다.

**배경 원인 — 장애가 일어날 수 있는 상태를 만든 것**
- **C1** `ReportService.buildClassReport` 는 메서드 전체가 `@Transactional` 이다. DB 조회가 끝난 뒤 같은 트랜잭션 안에서 `sign()`(SHA-256 20만 회)을 실행하므로, CPU 작업 내내 커넥션을 쥔다.
- **C2** 커넥션 풀은 모든 API 가 함께 쓰는 5개이고, 대기 한도는 3초다. 특정 엔드포인트가 풀을 독점하는 것을 막는 장치가 없다.
- **C3** 장애 전 리포트 요청은 분당 2건 이하였다. 그래서 C1 이 있어도 동시 점유가 1개 안팎에 그쳤고, leak 경고도 0건이었다.

**촉발 — 장애가 시작된 계기**
- **C4** 14:38 부터 교사 client 4곳이 리포트를 거의 30초 간격으로 반복 요청했다. 리포트 요청이 분당 3 → 3 → 10 → 최대 19건으로 늘었다. 늘어난 이유는 확인하지 못했다(9절).

**직접 원인 — 실패를 일으킨 사건**
- **C5** 리포트 요청마다 커넥션을 임계 10초를 넘겨 쥐었다(leak 경고 134건, 전부 리포트 경로, 전부 나중에 반납). 쥐고 있는 커넥션은 2 → 3 → 5개로 늘었다.
- **C6** 14:40:52 풀 5개가 모두 사용 중(`active=5, idle=0, waiting=4`)이 되었다. 커넥션을 3초 안에 얻지 못한 요청이 `CannotCreateTransactionException` 으로 실패해 500 을 받았다. 실패한 경로는 대부분 리포트가 아닌 단원 · 문항 조회였다.

**증폭 — 앱 오류가 사용자 오류로 커진 과정**
- **C7** nginx 는 응답이 늦은 `API-1` 에서 504 를 냈고, 이어 이 서버를 일시 비활성화했다. 예비 업스트림 `API-2` 는 연결을 거부했다. 그 결과 `no live upstreams` 502 가 앱 500(49건)보다 많은 137건(502 82 · 504 55)이 되었다.

**회복**
- **C8** 14:51 리포트 요청이 분당 5건으로 줄면서 마지막 leak 경고(14:51:37)와 마지막 5xx(14:51:47)가 지났다. 14:52 부터 평소 수준이다. 요청이 줄어든 이유는 로그로 알 수 없다.

## 5. 근거 로그 줄

| 문장 | 파일:줄번호 | 발췌 |
|---|---|---|
| C1 | `modern/api/…/assignment/ReportService.java:41`, `:71`, `:23` | `@Transactional` · `String signature = sign(payload.toString());` · `SIGNATURE_ROUNDS = 200_000` |
| C1 | `ReportService.java:69-70` | `TODO: 트랜잭션 밖으로 … 커넥션(트랜잭션)을 쥔 채로 돈다.` |
| C2 | `modern/api/src/main/resources/application.yml:14`, `:15`, `:18` | `maximum-pool-size: 5` · `connection-timeout: 3000` · `leak-detection-threshold: 10000` |
| C3 | `app.log:1148` | `14:10:05 … built class report for class 3: 3 distributions, 10 submissions` (장애 전 50건, leak 0건 — 3.1절) |
| C4 | `nginx-access.log:18801` | `교사단말A … [14:38:01] "GET /api/classes/3/report HTTP/1.1" 200` (이후 분당 집계는 3.1절 H3) |
| C5 | `app.log:1243` | `14:37:52 WARN … ProxyLeakTask : Connection leak detection triggered for …Connection@6e8f14d3 on thread http-nio-8080-exec-6` |
| C5 | `app.log:1258`, `:1259` | `14:38:01.900 … built class report for class 3` → `Previously reported leaked connection … was returned to the pool (unleaked)` |
| C6 | `app.log:1522` | `14:40:52 ERROR … itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| C6 | `app.log:1523`, `nginx-access.log:18918` | `unhandled exception on /api/units/M6-2/items` · `"GET /api/units/M6-2/items HTTP/1.1" 500` |
| C7 | `nginx-error.log:6`, `nginx-access.log:18933` | `14:41:06 upstream timed out (110: Connection timed out)` · `"GET /api/classes/3/report HTTP/1.1" 504` |
| C7 | `nginx-error.log:10`, `:11`, `:12` | `upstream server temporarily disabled` · `connect() failed (111: Connection refused) … upstream: "http://API-2:8080…"` · `no live upstreams` |
| C8 | `app.log:5251`, `nginx-access.log:19381` | `14:51:37 … leak detection triggered` · `[14:51:47] "GET /api/classes/3/report HTTP/1.1" 504` (마지막 5xx) |

## 6. 수정안 (지금 당장 적용)

코드는 아직 고치지 않았다. 아래는 제안이다.

1. **서명 계산을 트랜잭션 밖으로 뺀다** — `modern/api/src/main/java/com/example/assignment/ReportService.java`
   - DB 조회 · 집계(`:43-67`)는 새 빈의 `@Transactional(readOnly = true)` 메서드로 옮긴다. 예: `ReportDataReader.read(classId)` 가 학급 · 배포 수 · 제출 수 · 평균 · payload 를 record 로 반환한다. 같은 클래스 안에서 호출하면 프록시를 거치지 않으므로 별도 빈으로 둔다.
   - `buildClassReport` 에서 `@Transactional`(`:41`)을 제거한다. 위 메서드를 호출한 뒤, 트랜잭션 밖에서 `sign()`(`:71`)을 실행하고 `ClassReport` 를 만든다.
   - 효과: 커넥션 점유 시간이 조회 시간으로만 줄어든다. 응답 시간(CPU)은 그대로다.
2. **제출 조회를 루프 밖 한 번으로 바꾼다** — `SubmissionRepository.java:8`, `ReportService.java:52-54`
   - 배포마다 호출하는 `findByDistributionIdOrderByStudentIdAsc` 를 `findByDistributionIdInOrderByDistributionIdAscStudentIdAsc(List<Integer>)` 한 번으로 바꾼다. CLAUDE.md 4절의 N+1 금지에 해당하는 수정이다.
3. **테스트를 추가한다** — `ReportService` Mockito 테스트. `sign()` 을 트랜잭션 밖에서 호출하는지, 저장소 호출이 1회인지 검증한다. `@DisplayName` 은 한국어로 쓴다.
4. 설정(`application.yml`)은 이번 수정에서 바꾸지 않는다. 풀 크기를 늘리면 증상이 늦게 나타날 뿐 원인은 남는다. 바꾸게 되면 CLAUDE.md 에 따라 먼저 묻는다.

## 7. 재발 방지 (구조적 장치)

1. **규칙화**: CLAUDE.md 4절의 "`@Transactional` 메서드 안의 외부 HTTP 호출 금지"를 "외부 호출 · CPU 집약 작업(해시 반복 · 대량 직렬화 · 파일 처리) 금지"로 넓힌다. `convention-check` · `docs/approval-checklist.md` 에도 예/아니오 항목으로 넣는다.
2. **자동 검출 테스트**: 테스트 프로필에서 `leak-detection-threshold` 를 짧게 두고 `ProxyLeakTask` WARN 이 한 건이라도 찍히면 실패하는 통합 테스트를 둔다. 리포트처럼 무거운 엔드포인트는 동시 호출 시나리오를 함께 넣는다.
3. **격리**: 리포트처럼 무거운 읽기 경로가 학생 조회용 풀을 독점하지 못하게 한다. 리포트 전용 동시 실행 제한(세마포어 · 벌크헤드)이나 결과 캐시를 둔다. 라이브러리가 필요하면 의존성 추가이므로 먼저 묻는다.
4. **업스트림 구성 점검**: 한 대의 지연이 `no live upstreams` 로 번지지 않게 nginx `max_fails` · `fail_timeout` · 예비 서버(`API-2`)의 상태 점검을 운영 담당과 정한다.
5. **호출 측 합의**: 교사 화면의 리포트 자동 재요청(약 30초 간격)이 의도된 동작인지 확인한다. 실패 시 재시도에 백오프를 두도록 lms 팀과 합의한다.

## 8. 모니터링 항목

이번 타임라인에 적용했다면 경보가 울렸을 시각을 함께 적는다. 첫 사용자 실패는 14:40:52 다.

| 지표 | 임계값 제안 | 이번에 울렸을 시각 | 근거 |
|---|---|---|---|
| Hikari leak 경고(`ProxyLeakTask` WARN) | 5분에 1건 이상 | **14:37:52** (실패 3분 전) | `app.log:1243` |
| 슬로 쿼리 | `Query_time` 5초 이상 1건 | **14:37:52** | `mariadb-slow.log:15-18` |
| 리포트 요청 수 | 분당 6건 이상(평소 최대 2건의 3배) | **14:40 분** (10건) | 3.1절 H3 |
| 풀 획득 실패(`Connection is not available`) | 분당 1건 이상 | **14:40:52** | `app.log:1522` |
| 5xx 비율 | 1분 동안 5% 이상 | **14:41 분** (9/42 = 21%). 14:40 분은 1/42 = 2.4% 로 미달 | 1.2절 표 |
| nginx 업스트림 비활성 · `no live upstreams` | 1건 이상 | **14:42:01** | `nginx-error.log:10` |
| 풀 대기 스레드(`hikaricp.connections.pending`) | 0 초과가 30초 지속 | 판단 불가: 지표가 없다. `app.log:1522` 의 `waiting=4` 로 보아 늦어도 14:40:49 부터 대기가 있었다 | — |
| 리포트 응답 시간 p95 | 5초 초과 | 판단 불가: access 로그에 `$request_time` 이 없다 | — |

- 풀 지표 두 줄을 수집하려면 Actuator · Micrometer 가 필요하다. 현재 `modern/api/build.gradle:22-28` 에는 없으므로 의존성 추가로 먼저 묻는다.
- 가장 이른 신호는 leak 경고와 슬로 쿼리(14:37:52)다. 둘 다 이번 장애 전에는 평소 0건이거나 드물었으므로 오경보 부담이 작다.

## 9. 확인하지 못한 것

| 판단하지 못한 것 | 이유 | 다음을 위해 남길 로그 |
|---|---|---|
| 장애 전 10초 미만이던 리포트 점유가 15 ~ 21초로 늘어난 이유(CPU 경합 추정) | CPU · 스레드 지표 없음 | 앱 서버 CPU 사용률, JVM 스레드 상태, 요청별 처리 시간 |
| 운영 풀 · leak 임계가 `application.yml` 과 같은지 | 근거가 `application.yml:11` 주석뿐 | 기동 시 Hikari 설정 덤프(INFO 1줄) |
| 리포트 요청이 14:38 에 늘었다가 14:51 에 줄어든 이유 | 호출 측 코드가 저장소에 없음 | lms 교사 화면의 요청 로그 · 배포 이력 |
| 다른 날에도 같은 시간대에 리포트가 몰리는지 | 이 폴더에 하루치 로그만 있음 | 최소 1주치 `nginx-access.log` |
| 슬로 쿼리 기각의 전제(로그 상시 기록 · 샘플링 없음) | 그날 DB 변수 기록 없음 | `long_query_time` · `log_slow_rate_limit` · `slow_query_log` 값 |
| 인덱스가 있는데 128만 행을 스캔한 이유 | 운영 스키마 · 실행 계획 없음 | 운영 `SHOW INDEX FROM submission`, 해당 쿼리 `EXPLAIN` |
| 업스트림 정상 요청의 분산, `API-2` 상태 | access 로그에 `$upstream_addr` 없음, nginx 설정 · `API-2` 앱 로그 없음 | `$upstream_addr` · `$upstream_response_time` · `$request_time` 을 넣은 log_format, `API-2` 앱 로그 |

---

마스킹 적용: IP 주소 51건 · 이메일 0건 · 학생 식별자 1건 · 토큰 0건 · 비밀번호 · 접속 문자열 0건 (IP 는 역할 별칭 API-1 · API-2 · 교사단말A ~ D 로 바꿨다)
