package com.example.item;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 문항 검색 전용 저장소. 조건 조합은 {@link ItemSearchSpecifications} 가 만든다. */
public interface ItemSearchRepository extends Repository<Item, Integer>, JpaSpecificationExecutor<Item> {

    /** 조건에 맞는 한 페이지. 단원은 함께 읽는다(OSIV 꺼져 있음). 태그는 {@link #findTagNamesByItemIds} 로 따로 읽는다. */
    @Override
    @EntityGraph(attributePaths = "unit")
    Page<Item> findAll(Specification<Item> spec, Pageable pageable);

    /** 문항 id 목록의 태그 이름. 태그 id 오름차순. */
    @Query("""
        select i.id as itemId, t.name as tagName
        from Item i join i.tags t
        where i.id in :itemIds
        order by t.id asc
        """)
    List<ItemTagName> findTagNamesByItemIds(@Param("itemIds") Collection<Integer> itemIds);

    interface ItemTagName {

        Integer getItemId();

        String getTagName();
    }
}
