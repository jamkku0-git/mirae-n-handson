package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 저장소 슬라이스 — H2(MariaDB 모드). 조건 조합 · 정렬 · 페이지를 확인한다.
 * H2 는 콜레이션이 달라 대소문자 무시 · 한글 정렬 순서는 여기서 확인하지 못한다(동작 보존 테스트가 확인).
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemSearchRepository itemSearchRepository;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분수 덧셈", 2, ItemStatus.ACTIVE, calc, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 분수", 3, ItemStatus.REVIEWING));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 분수", 3, ItemStatus.DELETED));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 1, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    private Page<Item> search(String... nameValuePairs) {
        Map<String, List<String>> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            params.computeIfAbsent(nameValuePairs[i], k -> new java.util.ArrayList<>()).add(nameValuePairs[i + 1]);
        }
        ItemSearchCriteria criteria = ItemSearchCriteria.from(params);
        return itemSearchRepository.findAll(
            ItemSearchSpecifications.from(criteria),
            PageRequest.of(criteria.page() - 1, ItemSearchService.PAGE_SIZE, criteria.sort()));
    }

    private static List<String> titles(Page<Item> page) {
        return page.getContent().stream().map(Item::getTitle).toList();
    }

    @Test
    @DisplayName("조건 없음: 공개 문항만, 난이도 5 제외, level DESC · id ASC")
    void noConditionExcludesLevelFiveAndNonActive() {
        Page<Item> page = search();

        assertThat(titles(page)).containsExactly("분수 문장제", "분수 덧셈", "소수 곱셈");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("난이도 5 를 명시하면 난이도 5 문항이 나온다")
    void explicitLevelFiveIsIncluded() {
        assertThat(titles(search("level", "5"))).containsExactly("난이도 5 분수");
    }

    @Test
    @DisplayName("범위 밖 난이도는 오류가 아니라 0건")
    void outOfRangeLevelReturnsNothing() {
        assertThat(search("level", "6").getTotalElements()).isZero();
        assertThat(search("level", "abc").getTotalElements()).isZero();
        assertThat(search("level", "-1").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("키워드는 제목 또는 지문에서 부분 일치, % · _ 는 와일드카드")
    void keywordMatchesTitleOrStemWithWildcards() {
        assertThat(titles(search("q", "문장제"))).containsExactly("분수 문장제");
        assertThat(titles(search("q", "곱셈 문제 본문"))).containsExactly("소수 곱셈");
        assertThat(search("q", "_").getTotalElements()).isEqualTo(3);
        assertThat(search("q", "존재하지않는문장").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("단원 · 태그 조건은 AND 로 결합되고, 없는 단원 · 태그는 0건")
    void unitAndTagAreCombinedWithAnd() {
        assertThat(titles(search("unit", "M5-1", "tag", "문장제"))).containsExactly("분수 문장제", "분수 덧셈");
        assertThat(titles(search("unit", "M5-1", "tag", "문장제", "level", "4"))).containsExactly("분수 문장제");
        assertThat(search("unit", "Z9-9").getTotalElements()).isZero();
        assertThat(search("tag", "없는태그").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("정렬: 제목 내림차순, 단원 오름차순(같은 단원은 난이도 내림차순)")
    void sortsByRequestedKey() {
        assertThat(titles(search("sort", "title", "dir", "desc"))).containsExactly("소수 곱셈", "분수 문장제", "분수 덧셈");
        assertThat(titles(search("sort", "unit"))).containsExactly("분수 문장제", "분수 덧셈", "소수 곱셈");
        assertThat(titles(search("sort", "id", "dir", "desc"))).containsExactly("소수 곱셈", "분수 문장제", "분수 덧셈");
    }

    @Test
    @DisplayName("마지막 페이지를 넘으면 행은 비어 있어도 전체 건수는 그대로")
    void pageBeyondLastKeepsTotal() {
        Page<Item> page = search("page", "5");

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("태그 이름 조회: 태그 id 오름차순, 문항별로 모으면 문항의 태그 순서")
    void findsTagNamesInTagIdOrder() {
        List<Integer> ids = search("unit", "M5-1", "tag", "문장제").getContent().stream().map(Item::getId).toList();

        List<ItemSearchRepository.ItemTagName> rows = itemSearchRepository.findTagNamesByItemIds(ids);

        // 전체가 태그 id 순이므로, 문항별로 모으면 각 문항의 태그도 id 순이다.
        assertThat(rows).extracting(ItemSearchRepository.ItemTagName::getTagName)
            .containsExactly("계산", "문장제", "문장제");
        assertThat(rows).filteredOn(row -> row.getItemId().equals(ids.get(1)))
            .extracting(ItemSearchRepository.ItemTagName::getTagName).containsExactly("계산", "문장제");
    }
}
