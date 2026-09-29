package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/** 레거시 search.php 의 입력 처리(BR-03~09, 11~12, 15~19, 24~25)를 값별로 확인한다. DB 없이 돈다. */
@ActiveProfiles("test")
class ItemSearchCriteriaTest {

    private static ItemSearchCriteria criteria(String... nameValuePairs) {
        Map<String, List<String>> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            params.computeIfAbsent(nameValuePairs[i], k -> new java.util.ArrayList<>()).add(nameValuePairs[i + 1]);
        }
        return ItemSearchCriteria.from(params);
    }

    private static List<String> orders(ItemSearchCriteria criteria) {
        return criteria.sort().stream().map(o -> o.getProperty() + " " + o.getDirection()).toList();
    }

    @Test
    @DisplayName("파라미터가 없으면 조건 없음 · 기본 정렬 · 1페이지")
    void emptyParamsMeansNoConditions() {
        ItemSearchCriteria c = criteria();

        assertThat(c.keyword()).isNull();
        assertThat(c.unitCode()).isNull();
        assertThat(c.level()).isNull();
        assertThat(c.tag()).isNull();
        assertThat(c.page()).isEqualTo(1);
        assertThat(orders(c)).containsExactly("level DESC", "id ASC");
    }

    @Test
    @DisplayName("빈값 · 공백만 있는 값은 없는 것과 같다")
    void blankValuesAreIgnored() {
        ItemSearchCriteria c = criteria(
            "q", "   ", "unit", "", "level", "", "tag", " ", "sort", "", "dir", "", "page", "");

        assertThat(c).isEqualTo(criteria());
    }

    @Test
    @DisplayName("난이도 1~5 는 그대로, 범위 밖은 앞부분 숫자를 정수로 바꾼다")
    void levelIsParsedLikePhp() {
        assertThat(criteria("level", "5").level()).isEqualTo(5);
        assertThat(criteria("level", "6").level()).isEqualTo(6);
        assertThat(criteria("level", "03").level()).isEqualTo(3);
        assertThat(criteria("level", "3.5").level()).isEqualTo(3);
        assertThat(criteria("level", "3abc").level()).isEqualTo(3);
        assertThat(criteria("level", "abc").level()).isEqualTo(0);
        assertThat(criteria("level", "-1").level()).isEqualTo(-1);
        assertThat(criteria("level", "99999999999999999999").level()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("키워드는 앞뒤 공백을 빼고 100글자로 자른다")
    void keywordIsTrimmedAndTruncated() {
        assertThat(criteria("q", "  분수  ").keyword()).isEqualTo("분수");
        assertThat(criteria("q", "가".repeat(100)).keyword()).hasSize(100);
        assertThat(criteria("q", "가".repeat(101)).keyword()).isEqualTo("가".repeat(100));
    }

    @Test
    @DisplayName("이모지처럼 두 칸을 차지하는 글자도 한 글자로 센다")
    void truncationCountsCodePoints() {
        String emoji = "😀".repeat(101);

        assertThat(criteria("q", emoji).keyword()).isEqualTo("😀".repeat(100));
    }

    @Test
    @DisplayName("태그는 50글자로 자르고, 같은 이름이 반복되면 마지막 값을 쓴다")
    void tagIsTruncatedAndLastValueWins() {
        assertThat(criteria("tag", "가".repeat(60)).tag()).isEqualTo("가".repeat(50));
        assertThat(criteria("tag", "계산", "tag", "개념").tag()).isEqualTo("개념");
    }

    @Test
    @DisplayName("단원 코드는 대소문자를 바꾸지 않고 그대로 쓴다")
    void unitCodeIsKeptAsIs() {
        assertThat(criteria("unit", " m5-1 ").unitCode()).isEqualTo("m5-1");
    }

    @Test
    @DisplayName("페이지: 숫자가 아니거나 1 미만이면 1, 999 초과면 999")
    void pageIsClamped() {
        assertThat(criteria("page", "0").page()).isEqualTo(1);
        assertThat(criteria("page", "-1").page()).isEqualTo(1);
        assertThat(criteria("page", "abc").page()).isEqualTo(1);
        assertThat(criteria("page", "5").page()).isEqualTo(5);
        assertThat(criteria("page", "999").page()).isEqualTo(999);
        assertThat(criteria("page", "1000").page()).isEqualTo(999);
        assertThat(criteria("page", "99999999999999999999").page()).isEqualTo(999);
    }

    @Test
    @DisplayName("정렬: 기준별 기본 방향과 동순위 규칙")
    void sortDefaultsPerKey() {
        assertThat(orders(criteria("sort", "id"))).containsExactly("id ASC");
        assertThat(orders(criteria("sort", "title"))).containsExactly("title ASC", "id ASC");
        assertThat(orders(criteria("sort", "unit"))).containsExactly("unit.code ASC", "level DESC", "id ASC");
        assertThat(orders(criteria("sort", "level"))).containsExactly("level DESC", "id ASC");
        assertThat(orders(criteria("sort", "created"))).containsExactly("createdAt DESC", "id ASC");
    }

    @Test
    @DisplayName("정렬: 방향을 바꿔도 동순위의 id 는 오름차순(id 정렬만 예외)")
    void sortDirectionKeepsIdAscending() {
        assertThat(orders(criteria("sort", "id", "dir", "desc"))).containsExactly("id DESC");
        assertThat(orders(criteria("sort", "title", "dir", "desc"))).containsExactly("title DESC", "id ASC");
        assertThat(orders(criteria("sort", "unit", "dir", "desc")))
            .containsExactly("unit.code DESC", "level DESC", "id ASC");
        assertThat(orders(criteria("sort", "level", "dir", "asc"))).containsExactly("level ASC", "id ASC");
        assertThat(orders(criteria("sort", "created", "dir", "asc"))).containsExactly("createdAt ASC", "id ASC");
    }

    @Test
    @DisplayName("정렬: 대소문자는 무시하고, 알 수 없는 기준 · 방향은 기본값")
    void unknownSortFallsBack() {
        assertThat(orders(criteria("sort", "ID"))).containsExactly("id ASC");
        assertThat(orders(criteria("sort", "foo", "dir", "up"))).containsExactly("level DESC", "id ASC");
        assertThat(orders(criteria("sort", "title", "dir", "up"))).containsExactly("title ASC", "id ASC");
        assertThat(criteria("sort", "foo").sort()).isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("PHP 정수 변환: 앞부분 숫자만 읽는다")
    void phpIntValueReadsNumericPrefix() {
        assertThat(ItemSearchCriteria.phpIntValue(" 12x")).isEqualTo(12);
        assertThat(ItemSearchCriteria.phpIntValue("+7")).isEqualTo(7);
        assertThat(ItemSearchCriteria.phpIntValue("1e2")).isEqualTo(100);
        assertThat(ItemSearchCriteria.phpIntValue(".5")).isEqualTo(0);
        assertThat(ItemSearchCriteria.phpIntValue("x1")).isEqualTo(0);
    }
}
