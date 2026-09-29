# legacy/item-bank-php 비즈니스 규칙 (코드 기준 교차 검증용)

- 읽은 파일: `search.php`, `register.php`, `units.php`, `index.php`, `inc/db.php` (`inc/layout.php` · `vendor/` · `docs/` 는 읽지 않음)
- 근거 줄번호는 `legacy/item-bank-php/` 기준. 주석과 코드가 다르면 코드를 따랐다.
- 코드에 없는 규칙은 쓰지 않았다. 코드만으로 알 수 없는 것은 "확인 필요"로 구분했다.

## 1. 검색 조건 조합

### CX-01 — 조건은 모두 AND 로 결합된다
`WHERE 1=1` 에 키워드 · 단원 · 난이도 · 태그 조건이 `AND` 로만 붙고 OR 결합은 없다.
- 근거: `search.php:44`, `search.php:98`, `search.php:118`, `search.php:226`, `search.php:244`
```php
$where    = " WHERE 1=1";
$where .= " AND unit_code = ?";
```

### CX-02 — 키워드는 제목 또는 지문에 부분 일치(LIKE)하면 통과한다
`q` 는 `title LIKE %q%` OR `stem LIKE %q%` 이다. `%` · `_` 는 이스케이프하지 않아 와일드카드로 동작한다(경고만 표시).
- 근거: `search.php:94-100`
```php
$like = '%' . $q . '%';
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-03 — 키워드는 앞뒤 공백을 제거하고 100자까지만 쓴다
빈 문자열이면 조건을 추가하지 않는다. 100자 초과분은 잘라내고 경고한다.
- 근거: `search.php:85-90`
```php
$q = trim($q);
if (mb_strlen($q, 'UTF-8') > 100) {
    $q = mb_substr($q, 0, 100, 'UTF-8');
```

### CX-04 — 단원은 단원 코드와 정확히 일치해야 하며 입력값을 그대로 비교한다
`unit_code = ?`. 형식이 틀리거나 소문자여도 경고만 하고 대문자로 바꾸지 않은 채 그대로 조회한다(대개 0건). 등록되지 않은 코드도 조회는 실행한다.
- 근거: `search.php:109-120`, `search.php:141-143`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
    // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)
$where .= " AND unit_code = ?";
```

### CX-05 — 난이도를 지정하지 않으면 난이도 5 문항이 제외된다 (주석과 코드 불일치)
빈 값일 때 `level < 5` 를 붙인다. 바로 위 주석("1~5 모두 포함")과 파일 머리 주석("빈값 허용")은 전체 포함처럼 읽히지만, 코드는 5를 뺀다. 코드 기준 규칙: 난이도 미지정 검색 결과에는 level 5 가 나오지 않는다.
- 근거: `search.php:213-216`
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```

### CX-06 — 난이도 1~5 를 지정하면 그 값과 정확히 일치하는 문항만 나온다
지정하면 `level = ?` 이므로 5 도 조회된다(CX-05 의 제외는 미지정일 때만).
- 근거: `search.php:217-221`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
    $values[] = (int)$level;
```

### CX-07 — 난이도가 1~5 밖이면 경고 후 정수로 바꿔 그대로 비교한다
거부하지 않는다. 숫자가 아닌 값은 `(int)` 캐스팅으로 0 이 되어 `level = 0` 으로 조회된다(대개 0건).
- 근거: `search.php:223-229`
```php
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$where .= " AND level = ?";
$values[] = (int)$level;
```

### CX-08 — 태그는 이름이 정확히 일치하는 태그 1개를 가진 문항만 찾는다
`item_tag`·`tag` 를 EXISTS 로 조회한다. 부분 일치 없음, 태그 여러 개 동시 조건 없음. 50자 초과는 잘라낸다. 등록되지 않은 태그도 조회는 실행한다(경고만).
- 근거: `search.php:235-248`, `search.php:258-260`
```php
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-09 — 검색 대상은 테이블이 아니라 뷰 `v_item_public` 이다
목록과 건수 모두 이 뷰에서 조회한다. 어떤 행이 뷰에서 빠지는지(예: status 조건)는 이 폴더의 코드에 정의가 없어 **확인 필요**.
- 근거: `search.php:522`, `search.php:526`
```php
$from   = " FROM v_item_public";
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```

## 2. 목록 정렬 · 제외 · 페이지

### CX-10 — 기본 정렬은 난이도 내림차순, 같으면 ID 오름차순이다
`sort` 가 비었을 때 적용된다. 주석도 "어려운 문항부터, 같은 난이도면 번호 순".
- 근거: `search.php:317-320`
```php
case '':
    // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-11 — 알 수 없는 정렬 기준은 기본 정렬로 대체한다
`id|title|unit|level|created` 이외의 `sort` 는 경고 후 CX-10 과 같은 정렬을 쓴다. `dir` 이 `asc|desc` 가 아니면 경고 후 비운다.
- 근거: `search.php:268-273`, `search.php:322-326`
```php
default:
    $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-12 — 정렬 기준별 방향 기본값과 보조 정렬
`dir` 미지정 시: `level`·`created` 는 내림차순, 나머지는 오름차순이다. 동률은 `id ASC` 로 푼다. `unit` 정렬은 `unit_code` 다음 `level DESC` 를 쓰며, `dir=desc` 여도 `level` 은 DESC 그대로다.
- 근거: `search.php:293-299`, `search.php:301-307`, `search.php:328-331`
```php
$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
$orderBy = " ORDER BY level DESC, id ASC";   // level: asc 가 아니면 모두 DESC
```

### CX-13 — 한 페이지는 20건이고 페이지 번호는 최대 999 이다
`LIMIT 20 OFFSET (page-1)*20`. 숫자가 아니면 1 페이지, 999 초과는 999 로 고정한다. 따라서 이론상 19,980건 이후의 행은 페이지로 볼 수 없다.
- 근거: `search.php:336-349`, `search.php:523`
```php
if ($page > 999) {
    $page = 999;
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

### CX-14 — 단원 목록의 문항 수는 status='A' 문항만 센다
삭제 · 검수중 문항은 세지 않는다.
- 근거: `units.php:15-17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

## 3. 등록 검증 (register.php)

모든 검증 오류를 한 번에 모아 보여 주고, 오류가 하나라도 있으면 저장하지 않는다(`register.php:83`).

### CX-15 — 제목은 앞뒤 공백 제거 후 5자 이상 200자 이하이다
공백만 제거하고 글자 수는 `mb_strlen` 으로 센다(중간 공백은 글자 수에 포함).
- 근거: `register.php:41`, `register.php:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) {
if (mb_strlen($title, 'UTF-8') > 200) {
```

### CX-16 — 지문은 필수이다 (길이 상한 없음)
trim 후 빈 문자열이면 오류. 최대 길이 검사는 코드에 없다.
- 근거: `register.php:42`, `register.php:56-58`
```php
if ($stem === '') {
    $errors[] = '지문을 입력해야 합니다.';
```

### CX-17 — 단원은 필수이며 `unit` 테이블에 존재하는 id 여야 한다
전달값을 단원 목록의 `id` 와 문자열로 비교한다. (검색은 단원 *코드*, 등록은 단원 *id* 를 쓴다.)
- 근거: `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) {
    $unitOk = true;
```

### CX-18 — 난이도는 1~5 정수여야 한다
정규식 `^[1-5]$` 만 통과한다. 검색과 달리 범위 밖 값을 저장하지 않고 거부한다.
- 근거: `register.php:69-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
    $errors[] = '난이도는 1~5 사이여야 합니다.';
```

### CX-19 — 태그는 선택 사항이고, 목록에 없는 태그 id 는 오류 없이 버려진다
유효한 id 만 `item_tag` 에 저장한다. 태그가 0개여도 등록된다.
- 근거: `register.php:73-81`, `register.php:104-113`
```php
if ((string)$t['id'] === (string)$tid) {
    $validTagIds[] = (int)$tid;
```

### CX-20 — 새 문항은 상태 'R'(검수중)로 저장된다
INSERT 에 status 를 `'R'` 로 고정한다. 주석은 "검수 완료 전까지 검색 화면에 나오지 않는다"고 하지만, 검색 쪽 제외 로직은 `v_item_public` 정의에 있어 이 폴더 코드로는 확인할 수 없다 → **확인 필요**.
- 근거: `register.php:84`, `register.php:92-95`
```php
"INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
 VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
```

## 4. 범위에서 확인된 공백

- **수정 검증 없음**: 요청 범위에 "수정 시의 검증"이 있으나, `legacy/item-bank-php` 안에는 문항을 수정(UPDATE)하는 코드가 없다. 등록(`register.php`)만 있다.
- **`v_item_public` 정의 · status 코드('A'/'R'/삭제) 의미**: 뷰 DDL 이 이 폴더 밖이라 읽지 않았다. 검수중 · 삭제 문항이 검색에서 빠지는지는 확인 필요.
- 태그 중복 id 를 보냈을 때의 동작, 제목 · 지문 중복 검사는 코드에 없다.
