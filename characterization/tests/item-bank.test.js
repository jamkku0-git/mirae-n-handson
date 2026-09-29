// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 규칙
// - 대상 주소는 lib/target.mjs 가 정한다(TARGET_BASE_URL, 없으면 레거시 기본 주소). 여기에 주소를 적지 않는다.
// - 기대값을 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 조회만 한다. 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
// - 겨냥한 규칙 ID 는 docs/item-bank/BUSINESS-RULES.md 의 BR-번호다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

const KEYWORD_MAX = 100; // BR-06
const TAG_MAX = 50; // BR-12
const LONG_CHAR = '가';

async function search(params) {
  return fetchNormalized(MODULE, PATH, params);
}

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('조건 없음 — 공개 문항만 · 난이도 5 제외 · 기본 정렬 · 20건 (BR-02 · 03 · 16 · 23)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('단원 + 난이도 + 태그를 함께 — AND 결합 (BR-01 · 04 · 09 · 11)', async () => {
      expect(await search({ unit: 'M5-1', level: 3, tag: '계산' })).toMatchSnapshot();
    });

    it('키워드 분수 + 제목 내림차순 — 부분 일치 · 동순위 id 오름차순 (BR-07 · 19 · 20)', async () => {
      expect(await search({ q: '분수', sort: 'title', dir: 'desc' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 5 명시 — 빈값에서는 빠지던 5 가 나온다 (BR-03 · 04)', async () => {
      expect(await search({ level: 5 })).toMatchSnapshot();
    });

    it('난이도 6 — 범위 바로 밖 (BR-05)', async () => {
      expect(await search({ level: 6 })).toMatchSnapshot();
    });

    it('난이도 03 — 정규식은 못 통과하지만 정수 3 으로 조회 (BR-05)', async () => {
      expect(await search({ level: '03' })).toMatchSnapshot();
    });

    it('키워드 한 글자 — 경고가 시작되는 길이 (BR-08)', async () => {
      expect(await search({ q: '수' })).toMatchSnapshot();
    });

    it('키워드 100자 — 자르기 직전 (BR-06)', async () => {
      expect(await search({ q: LONG_CHAR.repeat(KEYWORD_MAX) })).toMatchSnapshot();
    });

    it('키워드 101자 — 100자로 잘리는 첫 값 (BR-06)', async () => {
      expect(await search({ q: LONG_CHAR.repeat(KEYWORD_MAX + 1) })).toMatchSnapshot();
    });

    it('페이지 999 — 상한 그대로 (BR-25)', async () => {
      expect(await search({ page: 999 })).toMatchSnapshot();
    });

    it('페이지 1000 — 상한을 넘는 첫 값 (BR-25)', async () => {
      expect(await search({ page: 1000 })).toMatchSnapshot();
    });

    it('페이지 0 — 경고 없이 1페이지 (BR-24)', async () => {
      expect(await search({ page: 0 })).toMatchSnapshot();
    });

    it('마지막 페이지 밖 5페이지 — 건수는 나오고 행은 비어 있다 (BR-23 · 25 · 26 · 27)', async () => {
      expect(await search({ page: 5 })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('모든 파라미터가 빈값 — 폼이 실제로 보내는 형태 (BR-03 · 16 · 24)', async () => {
      expect(
        await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' }),
      ).toMatchSnapshot();
    });

    it('공백만 있는 키워드 — trim 뒤 조건이 빠진다 (BR-06 · 07)', async () => {
      expect(await search({ q: '   ' })).toMatchSnapshot();
    });

    it('결과 없는 키워드 — 안내 문구 · 표 없음 (BR-26)', async () => {
      expect(await search({ q: '존재하지않는문장xyz' })).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 난이도 — 정수 -1 로 조회해 0건 (BR-05)', async () => {
      expect(await search({ level: -1 })).toMatchSnapshot();
    });

    it('음수 페이지 — 정규식 탈락으로 1페이지 (BR-24)', async () => {
      expect(await search({ page: -1 })).toMatchSnapshot();
    });

    it('없는 단원 코드 — 형식은 맞고 등록되지 않음 (BR-09 · 10)', async () => {
      expect(await search({ unit: 'Z9-9' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 — 경고가 뜨지만 조회는 된다 (BR-09)', async () => {
      expect(await search({ unit: 'm5-1' })).toMatchSnapshot();
    });

    it('알 수 없는 정렬 기준과 방향 — 기본 정렬로 복귀 (BR-16 · 17 · 18)', async () => {
      expect(await search({ sort: 'foo', dir: 'up' })).toMatchSnapshot();
    });

    it('와일드카드 키워드 언더스코어 — 전부 일치 (BR-07 · 08)', async () => {
      expect(await search({ q: '_' })).toMatchSnapshot();
    });

    it('태그를 배열로 — 첫 값만 사용 (BR-11 · 15)', async () => {
      expect(await search({ tag: ['계산', '개념'] })).toMatchSnapshot();
    });

    it('태그 60자 — 50자로 잘리고 등록되지 않은 태그 (BR-12 · 13)', async () => {
      expect(await search({ tag: LONG_CHAR.repeat(TAG_MAX + 10) })).toMatchSnapshot();
    });
  });
});
