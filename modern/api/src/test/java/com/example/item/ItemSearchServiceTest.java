package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemSearchRepository itemSearchRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    private static ItemSearchRepository.ItemTagName tagName(int itemId, String name) {
        return new ItemSearchRepository.ItemTagName() {
            @Override
            public Integer getItemId() {
                return itemId;
            }

            @Override
            public String getTagName() {
                return name;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Page<Item> stubPage(List<Item> items, long total) {
        Page<Item> page = new PageImpl<>(items, org.springframework.data.domain.PageRequest.of(0, 20), total);
        when(itemSearchRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        return page;
    }

    @Test
    @DisplayName("search: 행에는 id · title · unit · level · tags 만 담기고 태그는 id 순으로 붙는다")
    void searchMapsRowsWithTags() {
        Item first = ItemFixtures.item(12, unit, "대분수의 덧셈", 3, ItemStatus.ACTIVE);
        Item second = ItemFixtures.item(13, unit, "대분수의 곱셈", 3, ItemStatus.ACTIVE);
        stubPage(List.of(first, second), 2);
        when(itemSearchRepository.findTagNamesByItemIds(List.of(12, 13)))
            .thenReturn(List.of(tagName(12, "계산"), tagName(12, "오답률높음"), tagName(13, "계산")));

        ItemSearchResponse response = itemSearchService.search(ItemSearchCriteria.from(Map.of()));

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.message()).isNull();
        assertThat(response.rows()).containsExactly(
            new ItemSearchResponse.Row(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음")),
            new ItemSearchResponse.Row(13, "대분수의 곱셈", "M5-1", 3, List.of("계산")));
    }

    @Test
    @DisplayName("search: 태그가 없는 문항은 빈 목록")
    void searchItemWithoutTagsHasEmptyTags() {
        stubPage(List.of(ItemFixtures.item(3, unit, "태그 없는 문항", 1, ItemStatus.ACTIVE)), 1);
        when(itemSearchRepository.findTagNamesByItemIds(List.of(3))).thenReturn(List.of());

        ItemSearchResponse response = itemSearchService.search(ItemSearchCriteria.from(Map.of()));

        assertThat(response.rows()).extracting(ItemSearchResponse.Row::tags).containsExactly(List.of());
    }

    @Test
    @DisplayName("search: 0건이면 안내 문구를 담고 태그를 조회하지 않는다")
    void searchWithNoResultHasMessage() {
        stubPage(List.of(), 0);

        ItemSearchResponse response = itemSearchService.search(ItemSearchCriteria.from(Map.of()));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemSearchRepository, never()).findTagNamesByItemIds(any());
    }

    @Test
    @DisplayName("search: 마지막 페이지를 넘어도 건수는 전체 건수, 안내 문구는 없다")
    void searchBeyondLastPageKeepsTotal() {
        stubPage(List.of(), 20);

        ItemSearchResponse response = itemSearchService.search(ItemSearchCriteria.from(Map.of("page", List.of("5"))));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isEqualTo(20);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 페이지 번호와 정렬을 20건 단위 페이지 요청으로 넘긴다")
    void searchPassesPageRequest() {
        stubPage(List.of(), 0);

        itemSearchService.search(ItemSearchCriteria.from(Map.of("page", List.of("3"), "sort", List.of("title"))));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(itemSearchRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("title")).isNotNull();
    }
}
