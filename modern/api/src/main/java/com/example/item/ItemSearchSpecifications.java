package com.example.item;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** 검색 조건을 JPA 조건으로 바꾼다. 조건은 모두 AND 로 묶는다. */
final class ItemSearchSpecifications {

    /** 난이도를 지정하지 않았을 때 제외하는 최고 난이도(레거시 {@code level < 5}). */
    static final int EXCLUDED_LEVEL = 5;

    private ItemSearchSpecifications() {
    }

    static Specification<Item> from(ItemSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), ItemStatus.ACTIVE));

            if (criteria.keyword() != null) {
                // % · _ 를 이스케이프하지 않는다 — 레거시처럼 와일드카드로 동작한다.
                String pattern = "%" + criteria.keyword() + "%";
                predicates.add(cb.or(
                    cb.like(root.<String>get("title"), pattern),
                    cb.like(root.<String>get("stem"), pattern)));
            }
            if (criteria.unitCode() != null) {
                predicates.add(cb.equal(root.get("unit").get("code"), criteria.unitCode()));
            }
            if (criteria.level() == null) {
                predicates.add(cb.lessThan(root.<Integer>get("level"), EXCLUDED_LEVEL));
            } else {
                predicates.add(cb.equal(root.<Integer>get("level"), criteria.level()));
            }
            if (criteria.tag() != null) {
                Subquery<Integer> tagged = query.subquery(Integer.class);
                Root<Item> taggedItem = tagged.correlate(root);
                Join<Item, Tag> tag = taggedItem.join("tags");
                tagged.select(cb.literal(1)).where(cb.equal(tag.get("name"), criteria.tag()));
                predicates.add(cb.exists(tagged));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
