package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 컨트롤러 슬라이스 — 서비스는 목. 응답 필드 이름은 동작 보존 테스트의 정규화 결과와 같아야 한다. */
@WebMvcTest(ItemSearchController.class)
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    @Test
    @DisplayName("GET /api/items/search → 200, rows · count · message 만 담긴다")
    void searchReturnsRowsCountMessage() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(List.of(
            new ItemSearchResponse.Row(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음"))), 1));

        mockMvc.perform(get("/api/items/search").param("unit", "M5-1").param("level", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].id").value(12))
            .andExpect(jsonPath("$.rows[0].title").value("대분수의 덧셈"))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(3))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/items/search 0건 → 404 가 아니라 200, 안내 문구")
    void searchWithNoResultReturns200() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(List.of(), 0));

        mockMvc.perform(get("/api/items/search").param("level", "6"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    }

    @Test
    @DisplayName("GET /api/items/search 숫자가 아닌 level · page 도 400 이 아니라 200")
    void searchAcceptsNonNumericParams() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(List.of(), 0));

        mockMvc.perform(get("/api/items/search").param("level", "abc").param("page", "-1"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/items/search 같은 이름이 반복되면 마지막 값이 조건이 된다")
    void searchUsesLastValueOfRepeatedParam() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(List.of(), 0));

        mockMvc.perform(get("/api/items/search").param("tag", "계산", "개념")).andExpect(status().isOk());

        ArgumentCaptor<ItemSearchCriteria> captor = ArgumentCaptor.forClass(ItemSearchCriteria.class);
        verify(itemSearchService).search(captor.capture());
        assertThat(captor.getValue().tag()).isEqualTo("개념");
    }
}
