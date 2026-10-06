# 외주사 PR 판정 모음

기준: `templates/approval-checklist.md` v0, `CLAUDE.md`. 코드는 수정하지 않고 판정만 남긴다.

| PR | 판정 | 미충족 기준 | 근거(파일:줄번호) | 외주사에 돌려보낼 수정 요청 |
|---|---|---|---|---|
| 단원별 문항 수 통계 API 추가 (`HEAD~1..HEAD`, 커밋 `review: vendor pr-1 적용`, 3개 파일 +31 −1) | 반려 | ① 테스트 통과 ② 컨벤션 준수 | ① 새 엔드포인트 `GET /api/units/item-counts`(`modern/api/src/main/java/com/example/item/UnitController.java:25`)와 새 public 서비스 메서드(`UnitService.java:36`)에 테스트가 없다. 테스트 디렉터리에 `item-counts` · `countActive` 참조가 0건이고, 변경 파일 3개 중 테스트 파일이 없다. 또 이관 코드(`/api/units` 를 처리하는 Controller · Service 변경)에 해당하는데 `characterization` 은 실행하지 않았다(레거시 8081 응답 없음). ② 루프 안 Repository 호출(N+1): `UnitService.java:38-39` 에서 단원마다 `itemRepository.countByUnitIdAndStatus` 를 호출한다. 커넥션 풀 max 5 · timeout 3s 환경이라 단원 수만큼 쿼리가 나간다(CLAUDE.md §4). | (1) `UnitController` `@WebMvcTest`, `UnitService` Mockito 테스트를 추가하고 문항 없는 단원이 0 으로 나오는 경우와 `status='A'` 만 세는지를 검증한다(CLAUDE.md §2). (2) 단원별 개수를 `GROUP BY unit_id` 쿼리 1회로 가져오도록 `ItemRepository` 를 바꾸고, 서비스에서는 Map 으로 합쳐 N+1 을 없앤다. 새 쿼리는 `@DataJpaTest` 로 검증한다. (3) 수정 후 `./gradlew test` 와 `characterization` 결과(통과 · 실패 수)를 PR 에 첨부한다. |
