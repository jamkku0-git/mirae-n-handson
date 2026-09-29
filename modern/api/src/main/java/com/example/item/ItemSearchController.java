package com.example.item;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 검색 API. 파라미터(q, unit, level, tag, sort, dir, page)는 레거시 {@code search.php} 와 같다.
 * 값의 검증 · 보정은 {@link ItemSearchCriteria} 가 하므로, 여기서는 문자열 그대로 받는다.
 */
@RestController
@RequestMapping("/api")
public class ItemSearchController {

    private final ItemSearchService itemSearchService;

    public ItemSearchController(ItemSearchService itemSearchService) {
        this.itemSearchService = itemSearchService;
    }

    /** {@code GET /api/items/search} — 공개 문항 검색. */
    @GetMapping("/items/search")
    public ItemSearchResponse search(@RequestParam MultiValueMap<String, String> params) {
        return itemSearchService.search(ItemSearchCriteria.from(params));
    }
}
