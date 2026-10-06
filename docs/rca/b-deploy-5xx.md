# RCA — b-deploy-5xx: 배포 후 재배포 API 5xx 증가

> **작성 범위(이번 회차)**: 1. 수집 범위 · 2. 타임라인 · 3. 가설(가설 표까지)만 작성했다.
> 가설의 반증 실행 · 판정 · 코드 대조(4단계)와 요약 · 원인 · 수정안 · 재발 방지 · 모니터링 · 확인하지 못한 것은 **작성하지 않았다**.
> 이 문서에는 아직 원인 판정이 없다.

- 대상: `incident-logs/b-deploy-5xx/` (원본은 읽기만 했다)
- 작성일: 2026-10-06

---

## 1. 수집 범위

### 1.1 파일별 기본 정보

| 파일 | 줄 수 | 기록 기간 | 시각 형식 | 타임존 | 타임존 근거 |
|---|---|---|---|---|---|
| `app.log` | 2,938 (시각으로 시작하는 줄 1,275 · 스택트레이스 등 연속 줄 1,663) | 2026-09-17 09:00:04.878 ~ 14:39:57.905 (`app.log:1`, `app.log:2938`) | ISO-8601, 밀리초, 오프셋 포함 `2026-09-17T09:00:04.878+09:00` | KST (+09:00) | 각 줄에 `+09:00` 명시 |
| `nginx-access.log` | 20,365 | 2026-09-17 09:00:00 ~ 14:39:58 (`nginx-access.log:1`, `nginx-access.log:20365`) | combined, 초 단위 `[17/Sep/2026:09:00:00 +0900]` | KST (+0900) | 각 줄에 `+0900` 명시. 보조 근거: 분당 500 건수가 `app.log` 의 분당 ERROR 건수와 22개 분 모두 일치(1.3 표) |
| `deploy-history.md` | 39 | 2026-09-01 ~ 2026-09-17 배포 4건 | `YYYY-MM-DD HH:MM:SS` | KST | `deploy-history.md:3` "시각은 모두 KST(한국 표준시)입니다." |

### 1.2 시각 변환 규칙 (→ KST `YYYY-MM-DD HH:MM:SS[.mmm]`)

- `app.log`: `2026-09-17T13:46:38.113+09:00` → `2026-09-17 13:46:38.113` (오프셋이 이미 +09:00 이므로 `T` 를 공백으로, 오프셋만 제거).
- `nginx-access.log`: `[17/Sep/2026:13:46:38 +0900]` → `2026-09-17 13:46:38` (월 약어 → 숫자, 오프셋 제거). 초 단위이므로 `app.log` 와 같은 초 안의 선후는 판단하지 않는다.
  - nginx 는 요청 **완료** 시각을 기록하고 초 미만은 표시하지 않는다. 예: `app.log:2632` 14:28:05.990 의 요청이 `nginx-access.log:19662` 에 14:28:06 으로 찍혀 있다. 1초 이내 차이는 같은 요청으로 본다.
- `deploy-history.md`: 이미 KST. 그대로 쓴다.

### 1.3 이상 신호 집계 (분 단위)

이상 신호 = nginx 5xx 응답, `app.log` ERROR 줄. 실행한 명령:

```bash
join -a1 -a2 -e0 -o 0,1.2,2.2 \
  <(awk '$9>=500{split($4,a,":"); print a[2]":"a[3]}' nginx-access.log | sort | uniq -c | awk '{print $2,$1}') \
  <(grep -E '^2026\S+ +ERROR' app.log | cut -c12-16 | sort | uniq -c | awk '{print $2,$1}')
```

| 분(KST) | nginx 5xx | 상태코드 | app ERROR | 분(KST) | nginx 5xx | 상태코드 | app ERROR |
|---|---|---|---|---|---|---|---|
| 09:52 | 3 | 500 | 3 | 14:02 | 2 | 500 | 2 |
| 10:31 | 1 | 500 | 1 | 14:03 | 4 | 500 | 4 |
| 11:17 | 1 | 500 | 1 | 14:06 | 1 | 500 | 1 |
| 11:18 | 2 | 500 | 2 | 14:11 | 3 | 500 | 3 |
| 13:41 | 8 | **502** | **0** | 14:15 | 1 | 500 | 1 |
| 13:46 | 2 | 500 | 2 | 14:16 | 2 | 500 | 2 |
| 13:47 | 1 | 500 | 1 | 14:22 | 3 | 500 | 3 |
| 13:50 | 4 | 500 | 4 | 14:27 | 2 | 500 | 2 |
| 13:52 | 1 | 500 | 1 | 14:28 | 1 | 500 | 1 |
| 13:53 | 1 | 500 | 1 | 14:31 | 2 | 500 | 2 |
| 13:55 | 3 | 500 | 3 | 14:32 | 1 | 500 | 1 |
| 13:58 | 2 | 500 | 2 | 14:34 | 3 | 500 | 3 |

- 전체 상태코드(`awk '{print $9}' nginx-access.log | sort | uniq -c`): 200 19,278 · 404 969 · 400 29 · 409 35 · **500 46** · **502 8**.
- `app.log` ERROR 줄은 46건이고, 바로 다음 줄(예외 첫 줄)은 46건 모두 ``JpaObjectRetrievalFailureException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist`` 다. ERROR 46 = nginx 500 46.
- 502 8건은 13:41:51 ~ 13:41:59 에만 있고, 13:42:00 이후 502 는 0건이다(`awk` 집계 결과 `502 after 13:42:00: 0`).
- 시간대별 트래픽(nginx 줄 수): 09시 3,625 · 10시 3,621 · 11시 3,762 · 12시 3,215 · 13시 3,678 · 14시 2,464(14:39:58 까지). 분당 최소 40 · 최대 82.

이상 신호는 세 묶음으로 나뉜다(해석하지 않고 시각만 구분한다).

| 묶음 | 기간 | 건수 | 대상 경로 |
|---|---|---|---|
| G1 | 09:52:17 ~ 11:18:02 | 500 × 7 | `/api/distributions/41/redistribute` 6, `/api/distributions/41` 1 |
| G2 | 13:41:51 ~ 13:41:59 | 502 × 8 | 여러 경로(`/api/units`, `/api/items/5`, `/` 등) |
| G3 | 13:46:38 ~ 14:34:45 | 500 × 39 | `/api/distributions/42/redistribute` 19, `/api/distributions/43/redistribute` 20 |

- **첫 실패**: 2026-09-17 09:52:17 (`nginx-access.log:3150`, `app.log:194`)
- **마지막 실패**: 2026-09-17 14:34:45 (`nginx-access.log:20084`, `app.log:2887`)

### 1.4 수집 범위 확정

**2026-09-17 09:00:00 ~ 14:39:58 KST (두 로그 파일 전체) + `deploy-history.md` 의 v1.4.2 항목(`deploy-history.md:7`, `:12`~`:22`)**

- 앞 여유: 첫 실패 09:52:17 보다 52분 앞인 기록 시작(09:00:00)까지 포함한다. 같은 예외(`identifier value `9``)가 배포 전부터 나오므로, 배포 전 "평소" 재배포 요청의 성공(200) · 충돌(409) 비율을 비교 기준으로 써야 하기 때문이다. 기록이 09:00 에서 시작하므로 그 이전은 볼 수 없다.
- 뒤 여유: 마지막 실패 14:34:45 뒤 5분(14:39:45)을 잡으면 기록 끝(14:39:58)과 거의 같으므로 끝까지 포함한다. 이후 재발 여부는 로그가 없어 볼 수 없다.
- `deploy-history.md` 의 v1.4.1 · v1.4.0 · v1.3.9 (09-14, 09-08, 09-01) 는 수집 범위 밖이다. 직전 버전과 롤백 계획 확인용으로만 참조한다.

---

## 2. 타임라인 (KST)

표기: 같은 초이거나 nginx(초 단위)와 app(밀리초)의 몇 초 이내 차이로 선후를 단정할 수 없으면 **(선후 불확실)**. ★ = 첫 실패 · 최다 오류보다 앞서면서 평소와 다른 줄.

> ★ 판정 기준 메모: 전체 첫 실패(09:52:17, G1) 이전 `app.log:1`~`:193` 의 메시지는 모두 하루 내내 반복되는 종류(`not found`, `no handler for`, `redistributed distribution`, `built class report`, `state conflict` WARN, `bad request`)뿐이라 ★ 대상이 없다. 아래 ★ 는 **최다 오류 묶음 G3(39건) 및 G2 보다 앞서면서** 하루 중 1회만 나온 줄에 붙였다.

| 시각(KST) | 출처 `파일:줄번호` | 원문 발췌 | 비고 |
|---|---|---|---|
| 09:00:00 ~ | `nginx-access.log:1` 이후 | `[17/Sep/2026:09:00:00 +0900] "GET /api/units/M5-3/items HTTP/1.1" 200 1465` | 평소 트래픽. 분당 40~82줄 |
| 09:06:17.884 | `app.log:24` | `WARN ... state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3 (/api/distributions/3/redistribute)` | 이후 하루 동안 35건(배포 전 31 · 배포 후 4). nginx 409 와 대응 |
| 09:07:01.507 | `app.log:28` | `INFO ... redistributed distribution 8 (assignment 6, class 2)` | 재배포 성공 로그. 배포 전 재배포 200 응답 66건(크기 170~200 바이트) |
| 09:52:17.335 | `app.log:194` | `ERROR ... unhandled exception on /api/distributions/41/redistribute` | **G1 첫 실패** |
| 09:52:17.335 | `app.log:195` | ``JpaObjectRetrievalFailureException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist`` | |
| 09:52:17.335 | `app.log:204`~`:206`, `:214` | `$Proxy141.findWithDetailsById` ← `DistributionService.loadOrThrow(DistributionService.java:65)` ← `DistributionService.redistribute(DistributionService.java:51)` ← `DistributionController.redistribute(DistributionController.java:38)` | 스택 내 애플리케이션 프레임 |
| 09:52:17 | `nginx-access.log:3150` | `교사단말A ... "POST /api/distributions/41/redistribute HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions/41"` | app 줄과 같은 초 **(선후 불확실)** |
| 09:52:30, 09:52:41 | `nginx-access.log:3162`, `:3174` / `app.log:232`, `:270` | `POST /api/distributions/41/redistribute ... 500 176` | 같은 단말 · 같은 경로 반복. 09:52 3건/분 |
| 10:31:05.909 | `app.log:451`, `:452`, `:461`~`:463`, `:471` | `ERROR ... unhandled exception on /api/distributions/41` / 같은 예외(`identifier value `9``) / `findWithDetailsById` ← `loadOrThrow(DistributionService.java:65)` ← `getDistribution(DistributionService.java:32)` ← `DistributionController.getDistribution(DistributionController.java:26)` | 조회(GET) 경로에서도 같은 예외 |
| 10:31:05 | `nginx-access.log:5563` | `교사단말A ... "GET /api/distributions/41 HTTP/1.1" 500 176` | **(선후 불확실)** |
| 11:17:40 ~ 11:18:02 | `app.log:655`, `:695`, `:733` / `nginx-access.log:8442`, `:8466`, `:8468` | `POST /api/distributions/41/redistribute ... 500 176` | **G1 마지막**. 11:17 1건, 11:18 2건 |
| 10:36:16 ~ 13:41:20 | `app.log:510` (42 첫 성공) … `app.log:1238` (43 마지막 성공) | `redistributed distribution 42 (assignment 4, class 1)` / `redistributed distribution 43 (assignment 6, class 1) reason=학생A 요청 — 제출 화면 오류` | 배포 전 42 · 43(class 1) 재배포는 nginx 기준 42 → 200 5건, 43 → 200 13건 |
| 13:06:33, 13:24:45 | `app.log:1137`, `:1191` | `built class report for class 1: 3 distributions, 10 submissions` | class 1 리포트 배포 전 17건 모두 200. 배포 후 class 1 리포트 요청 0건 |
| ★ 13:38 | `deploy-history.md:21` | `승인: <EMAIL> (13:38)` | v1.4.2 승인 |
| ★ 13:40:05 | `deploy-history.md:7` | `v1.4.2 \| 2026-09-17 13:40:05 \| 2026-09-17 13:42:31 \| <EMAIL> \| 재배포 응답에 학급 배포 이력(history) 포함 — DistributionController.redistribute` | 배포 시작. 대상 호스트 API-1 (`deploy-history.md:3`) |
| ★ (13:40:05 기준) | `deploy-history.md:15`, `:16`, `:18` | `응답 형식 변경 {distribution} → {distribution, history[]}` / `DistributionController.redistribute 에서 재배포 후 DistributionService.listByClass(classId) 호출 추가` / `단일 호스트 재기동(무중단 아님). 예상 중단 10초 안팎.` | 배포 내용(시각 없음, 배포 항목에 속함) |
| ★ 13:41:48.214 | `app.log:1240` | `[tomcat-shutdown] o.s.b.w.e.tomcat.GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete` | 하루 중 1회 |
| ★ 13:41:50.144 ~ 13:41:50.224 | `app.log:1241`~`:1244` | `Graceful shutdown complete` / `Closing JPA EntityManagerFactory` / `itembank-pool - Shutdown initiated...` / `itembank-pool - Shutdown completed.` | |
| 13:41:51 | `nginx-access.log:16865` | `학생단말A ... "GET /api/units/M5-2/items?student=<STUDENT> HTTP/1.1" 502 157` | **G2 첫 502**. 이후 13:41:59 까지 8건/분(`nginx-access.log:16865`~`:16872`) |
| 13:41:52 | `nginx-access.log:16866` | `<IP> ... "GET / HTTP/1.1" 502 157 "-" "kube-probe/1.29"` | 헬스체크도 502. kube-probe 전체 867건 중 200 아닌 것은 이 1건 |
| 13:41:53 | `nginx-access.log:16867`~`:16869` | `학생단말B "GET /api/distributions/8?student=<STUDENT>" 502` / `교사단말C "GET /api/distributions/5" 502` / `학생단말C "GET /api/units" 502` | 같은 초 3건 **(선후 불확실)** |
| ★ 13:41:55.902 | `app.log:1245` | `Starting ItemBankApplication v1.4.2 using Java 21.0.4 with PID 1` | 13:41:55 의 502(`nginx-access.log:16870`, 학생단말D)와 같은 초 **(선후 불확실)** |
| ★ 13:41:58.351 ~ 13:41:58.535 | `app.log:1258`~`:1260` | `itembank-pool - Starting...` / `Added connection org.mariadb.jdbc.Connection@4c1e7a3b` / `Start completed.` | 13:41:58 의 502(`nginx-access.log:16871`, 학생단말E)와 같은 초 **(선후 불확실)** |
| 13:41:59 | `nginx-access.log:16872` | `학생단말F "GET /api/units/M6-1/items?student=<STUDENT>" 502 157` | **G2 마지막 502** |
| ★ 13:42:00.290 ~ 13:42:00.304 | `app.log:1263`, `:1264` | `Tomcat started on port 8080 (http) with context path '/'` / `Started ItemBankApplication in 4.921 seconds` | 이후 502 0건. 배포 이력의 "배포 완료 13:42:31"(`deploy-history.md:7`)보다 31초 이르다 |
| 13:42:10.447 | `app.log:1266` / `nginx-access.log:16884` | `redistributed distribution 7 (assignment 5, class 3)` / `교사단말B "POST /api/distributions/7/redistribute" 200 802` | 배포 후 첫 재배포 성공. 응답 크기 802 (배포 전 170~200) **(선후 불확실: 같은 초)** |
| 13:46:38.107 | `app.log:1274` | `INFO ... redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` | 재배포 처리 로그가 ERROR 보다 6ms 먼저 찍힘 |
| 13:46:38.113 | `app.log:1275` | `ERROR ... unhandled exception on /api/distributions/42/redistribute` | **G3 첫 실패(최다 오류 묶음 시작)** |
| 13:46:38.113 | `app.log:1276` | ``JpaObjectRetrievalFailureException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist`` | G1 과 같은 예외 · 같은 id |
| 13:46:38.113 | `app.log:1285`, `:1286`, `:1294` | `$Proxy141.findByClassRoomIdOrderByDistributedAtAsc` ← `DistributionService.listByClass(DistributionService.java:38)` ← `DistributionController.redistribute(DistributionController.java:40)` | G1 과 다른 호출 경로(`loadOrThrow` 아님) |
| 13:46:38.113 | `app.log:1304` | ``Caused by: org.hibernate.FetchNotFoundException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist`` | |
| 13:46:38 | `nginx-access.log:17155` | `교사단말A "POST /api/distributions/42/redistribute HTTP/1.1" 500 176` | **(선후 불확실)** |
| 13:46:58 ~ 13:47:08 | `app.log:1312`, `:1313`, `:1350`, `:1351` / `nginx-access.log:17170`, `:17181` | `redistributed distribution 42 ... reason=학생B 결석으로 재배포` → `ERROR ... /api/distributions/42/redistribute` | 42 반복. 13:46 2건 · 13:47 1건 |
| 13:50:00 | `app.log:1393` / `nginx-access.log:17353` | `redistributed distribution 8 (assignment 6, class 2)` / `교사단말A "POST /api/distributions/8/redistribute" 200 908` | 같은 교사단말A, class 2 는 200 |
| 13:50:12 ~ 13:50:43 | `app.log:1395`~`:1397`, `:1406`, `:1407`, `:1415` … `:1512` / `nginx-access.log:17365`, `:17366`, `:17376`, `:17400` | `redistributed distribution 43 (assignment 6, class 1)` → `ERROR ... /api/distributions/43/redistribute` (같은 예외, `listByClass(DistributionService.java:38)` ← `DistributionController.redistribute(DistributionController.java:40)`) | 43 첫 실패 13:50:12, 13:50 4건/분 |
| 13:51:08 | `app.log:1549` / `nginx-access.log:17424` | `redistributed distribution 7 (assignment 5, class 3)` / `교사단말B ... 200 891` | |
| 13:52 ~ 14:34 | `app.log:1555` … `app.log:2887` / `nginx-access.log:17516` … `:20084` | `ERROR ... unhandled exception on /api/distributions/{42,43}/redistribute` | 반복: 42·43 만, 1~4건/분, 매번 바로 앞에 `redistributed distribution 4{2,3} ... class 1` INFO. 묶음 합계 500 × 39 (42: 19, 43: 20), 전부 교사단말A |
| 13:56:43 | `app.log:1754` / `nginx-access.log:17734` | `redistributed distribution 44 (assignment 5, class 2)` / `교사단말A ... 200 640` | |
| 13:59:30 ~ 14:35:26 | `nginx-access.log:17867`, `:19451`, `:19786`, `:20119` | `POST /api/distributions/{3,2}/redistribute ... 409` | 배포 전과 같은 종류의 409, 4건 |
| 14:21:02, 14:22:46 | `app.log:2420`, `:2542` / `nginx-access.log:19238`, `:19347` | `redistributed distribution 5 (assignment 3, class 3)` / `교사단말B ... 200 910`, `200 641` | |
| 14:34:45.439 | `app.log:2887` / `nginx-access.log:20084` | `ERROR ... unhandled exception on /api/distributions/43/redistribute` / `교사단말A "POST /api/distributions/43/redistribute" 500 176` | **마지막 실패** |
| 14:39:58 | `nginx-access.log:20365` | `"GET /api/units HTTP/1.1" 200 613` | 기록 끝 |

배포 전후 재배포(`POST .../redistribute`) 집계(13:42:00 기준, nginx):

| 구간 | 200 | 409 | 500 | 500 대상 |
|---|---|---|---|---|
| 배포 전 09:00:00 ~ 13:41:59 | 66 | 31 | 6 | 41 만 |
| 배포 후 13:42:00 ~ 14:39:58 | 6 (7·8·44·5) | 4 (2·3) | 39 | 42 · 43 만 |

재배포 로그에 나온 배포 → 학급 대응(`grep -o 'redistributed distribution [0-9]* (assignment [0-9]*, class [0-9]*)' app.log | sort | uniq -c`): 42 · 43 → class 1, 44 · 8 → class 2, 5 · 6 · 7 → class 3. 41 은 재배포 로그 줄이 없어 학급을 알 수 없다(확인 필요).

---

## 3. 가설

### 3.1 증상 (원인이 아니라 관찰된 현상)

- **S1**: `POST /api/distributions/{42,43}/redistribute` 가 배포(v1.4.2) 이후 39건 모두 500 (`nginx-access.log:17155` … `:20084`). 같은 기간 다른 배포(5 · 7 · 8 · 44)의 재배포는 200.
- **S2**: 최다 오류 메시지 ``JpaObjectRetrievalFailureException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist`` 46건. 배포 전 7건(G1, 41번, `loadOrThrow` 경로)과 배포 후 39건(G3, 42 · 43번, `listByClass` 경로) (`app.log:195`, `:1276`).
- **S3**: 재기동 구간 13:41:51 ~ 13:41:59 에 경로 무관 502 8건 (`nginx-access.log:16865`~`:16872`).
- G3 의 매 실패 직전에 `redistributed distribution 4{2,3}` INFO 가 수 ms 앞서 찍힌다(`app.log:1274` → `:1275`).

타임아웃 메시지는 수집 범위에 없다(`grep -ci timeout incident-logs/b-deploy-5xx/app.log` → `0`). `hikari` 를 포함한 줄 5건은 모두 종료 · 기동 메시지다(`app.log:1243`, `:1244`, `:1258`~`:1260`).

### 3.2 가설 표

| ID | 계층 | 가설 | 지지 근거 (타임라인 줄) | 반증 조건 (이게 보이면 틀렸다) | 확인 방법 |
|---|---|---|---|---|---|
| H1 | 코드 (v1.4.2 변경) | v1.4.2 에서 `DistributionController.redistribute` 에 추가된 `listByClass(classId)` 호출이 같은 학급의 배포 이력 전체를 읽으면서, 존재하지 않는 Assignment(id 9)를 참조하는 배포 행까지 로딩해 예외가 난다. 재배포 처리 자체는 끝난 뒤 이력 조회 단계에서 500 이 된다. | `deploy-history.md:15`, `:16` (변경 내용) · `app.log:1274` → `:1275` (INFO 후 ERROR) · `app.log:1285`, `:1286`, `:1294` (`findByClassRoomIdOrderByDistributedAtAsc` ← `listByClass:38` ← `redistribute:40`) · 배포 전 43 성공 `app.log:1238` | ① 배포 후 class 1 배포(42 · 43)의 재배포가 200 으로 끝난 줄이 있다. ② 배포 후 500 의 스택에 `listByClass` 가 없는 건이 있다. ③ class 2 · 3 배포의 재배포가 배포 후 같은 예외로 실패한다. ④ 배포 전에도 `listByClass` 프레임이 있는 ERROR 가 있다. | `awk` 로 13:42:00 이후 재배포 상태코드를 배포 id 별로 집계 · `grep -n 'listByClass' app.log` 의 첫 줄 번호와 1264 비교 · ERROR 블록별 `DistributionController.redistribute(DistributionController.java:40)` 유무 집계 · (4단계) `modern/api/.../DistributionController.java:38`~`:40`, `DistributionService.java:38` 대조 |
| H2 | DB · 데이터 정합성 | DB 에 존재하지 않는 Assignment(id 9)를 가리키는 배포 행(고아 참조)이 배포 전부터 있었다. 배포 41이 그 행이며 41 이 class 1 에 속해 있어, class 1 을 통째로 읽는 쿼리는 모두 실패한다. 코드 변경은 이미 있던 불량 데이터를 새 경로로 드러냈을 뿐이다. | 배포 전 G1: `app.log:195`, `:204`~`:206` (41 단건 로딩 실패) · `app.log:452`, `:461`~`:463` (41 조회 실패) · 모든 예외의 id 가 `9` 하나(46/46) · 실패 배포 42 · 43 이 모두 class 1 (`app.log:1274`, `:1395`) | ① 배포 후 예외의 identifier 가 9 가 아닌 건이 있다. ② readonly 계정으로 조회했을 때 assignment id 9 가 존재한다. ③ 배포 41 이 class 1 이 아니다(또는 assignment_id 가 9 가 아니다). ④ class 1 리포트(`built class report for class 1`)가 같은 행을 읽고도 성공한다면 리포트 쿼리가 다른 조인 방식인지 따로 설명이 필요하다(`app.log:1191`). | `grep -o 'identifier value `[0-9]*`' app.log \| sort \| uniq -c` · readonly 계정으로 class 1 의 배포 행과 assignment id 9 존재 여부 조회(테이블 · 컬럼명은 확인 필요, DB 접근 가능할 때만) · 배포 41 의 학급을 알려 주는 로그 탐색 `grep -n 'distribution 41\|distributions/41' app.log nginx-access.log` |
| H3 | 인프라 · 배포 방식 | 무중단이 아닌 단일 호스트 재기동이 장애의 원인이다: 재기동 중 502 가 났고, 재기동 후 새 인스턴스의 상태(커넥션 풀 · 캐시 · 스키마 등) 문제로 500 이 이어진다. | `deploy-history.md:18` (단일 호스트 재기동, 무중단 아님) · `app.log:1240`~`:1244` (shutdown) · `nginx-access.log:16865`~`:16872` (502 8건) · `app.log:1245`, `:1263`, `:1264` (13:42:00 기동) | ① 13:42:00 이후 502 가 0건이다. ② 13:42:00 이후 500 이 특정 배포(42 · 43)에만 몰리고 다른 경로 · 다른 배포는 정상이다. ③ 배포 후 500 이 커넥션 · 타임아웃 계열이 아니라 데이터 예외 하나뿐이다. ④ 같은 예외가 재기동 전(G1)에도 있다. | 13:42:00 전후 502 · 500 분포 `awk` 집계 · 배포 후 500 의 예외 첫 줄 종류 집계 · `grep -ciE 'hikari\|timeout\|connection' app.log` 로 풀 관련 경고 유무 · 배포 후 경로별 5xx 비율 |
| H4 | 트래픽 · 클라이언트 | 특정 교사 단말(교사단말A)의 반복 재시도(10~20초 간격)가 실패를 부풀렸거나, 그 단말이 보내는 요청(본문 · 파라미터)이 비정상이다. | G3 500 39건 전부 교사단말A (`nginx-access.log:17155` … `:20084`) · 같은 배포에 3건씩 짧은 간격 반복 (`nginx-access.log:17155`, `:17170`, `:17181`) · G1 7건도 전부 교사단말A (`nginx-access.log:3150` … `:8468`) | ① 교사단말A 의 다른 배포(class 2: 8 · 44) 재배포는 배포 후 200 이다. ② 교사단말A 가 배포 전에 같은 42 · 43 을 재배포해 200 을 받았다. ③ 실패 수가 재시도 없이도(최초 요청 기준) 0 이 아니다. ④ 배포 후 분당 트래픽이 평소 범위(40~82줄/분)를 벗어나지 않는다. | 단말별 · 배포 id 별 · 배포 전후 상태코드 `awk` 집계 · 분당 요청 수 집계(13:35 ~ 14:39) · 같은 배포 id 의 연속 요청 간격 계산 |

- H1 과 H2 는 서로 배타적이지 않다(코드 경로 변경 × 기존 불량 데이터). 4단계에서 각각 따로 판정한다.
- 이번 회차에서는 반증 실행 · 판정 · 코드 대조를 하지 않았다. 위 "확인 방법"은 실행 전 계획이다.

---

마스킹 적용: IP 주소 25건 · 이메일 2건 · 학생 식별자 5건 · 토큰 0건 · 비밀번호 · 접속 문자열 0건 (IP 는 역할 별칭 API-1 · 교사단말A~C · 학생단말A~F 로, kube-probe 출발지는 `<IP>` 로 바꿨다)
