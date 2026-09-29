package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답. 필드 이름은 동작 보존 테스트의 정규화 결과({rows, count, message})와 같다.
 * {@code count} 는 조건에 맞는 전체 건수이고, 현재 페이지가 비어 있어도 전체 건수를 그대로 돌려준다.
 */
public record ItemSearchResponse(List<Row> rows, int count, String message) {

    /** 결과가 0건일 때의 안내 문구. 레거시 {@code #message} 와 같다. */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    /** 결과 표의 한 행. {@code unit} 은 단원 코드, {@code tags} 는 태그 이름(id 순). */
    public record Row(Integer id, String title, String unit, Integer level, List<String> tags) {
    }

    static ItemSearchResponse of(List<Row> rows, long total) {
        String message = total == 0 ? NO_RESULT_MESSAGE : null;
        return new ItemSearchResponse(rows, Math.toIntExact(total), message);
    }
}
