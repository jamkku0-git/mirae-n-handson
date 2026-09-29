package com.example.item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 문항 검색 서비스. 엔티티는 트랜잭션 안에서 DTO 로 바꿔 돌려준다(OSIV 꺼져 있음). */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    static final int PAGE_SIZE = 20;

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemSearchRepository itemSearchRepository;

    public ItemSearchService(ItemSearchRepository itemSearchRepository) {
        this.itemSearchRepository = itemSearchRepository;
    }

    /** 공개 문항 검색. 조건에 맞는 문항이 없거나 페이지가 범위를 넘어도 오류가 아니라 빈 목록이다. */
    public ItemSearchResponse search(ItemSearchCriteria criteria) {
        PageRequest pageRequest = PageRequest.of(criteria.page() - 1, PAGE_SIZE, criteria.sort());
        Page<Item> result = itemSearchRepository.findAll(ItemSearchSpecifications.from(criteria), pageRequest);

        Map<Integer, List<String>> tagNames = tagNamesByItemId(result.getContent());
        List<ItemSearchResponse.Row> rows = result.getContent().stream()
            .map(item -> new ItemSearchResponse.Row(
                item.getId(),
                item.getTitle(),
                item.getUnit().getCode(),
                item.getLevel(),
                tagNames.getOrDefault(item.getId(), List.of())))
            .toList();
        log.debug("item search page {} returned {} of {} items",
            criteria.page(), rows.size(), result.getTotalElements());
        return ItemSearchResponse.of(rows, result.getTotalElements());
    }

    private Map<Integer, List<String>> tagNamesByItemId(Collection<Item> items) {
        Map<Integer, List<String>> tagNames = new LinkedHashMap<>();
        if (items.isEmpty()) {
            return tagNames;
        }
        List<Integer> ids = items.stream().map(Item::getId).toList();
        for (ItemSearchRepository.ItemTagName row : itemSearchRepository.findTagNamesByItemIds(ids)) {
            tagNames.computeIfAbsent(row.getItemId(), id -> new ArrayList<>()).add(row.getTagName());
        }
        return tagNames;
    }
}
