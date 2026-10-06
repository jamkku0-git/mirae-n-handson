package com.example.item;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;

/**
 * 검색 파라미터를 정제한 조건. 레거시 {@code search.php} 의 입력 처리를 그대로 옮긴 것이다.
 * 잘못된 값은 오류로 막지 않고 레거시처럼 보정하거나 그대로 조회한다(그래서 결과가 0건이 될 수 있다).
 *
 * @param keyword  제목 · 지문에서 찾을 키워드. 없으면 {@code null}
 * @param unitCode 단원 코드. 없으면 {@code null}
 * @param level    난이도. {@code null} 이면 미지정(난이도 5 제외)
 * @param tag      태그 이름. 없으면 {@code null}
 * @param sort     정렬
 * @param page     1부터 시작하는 페이지 번호
 */
public record ItemSearchCriteria(String keyword, String unitCode, Integer level, String tag, Sort sort, int page) {

    static final int KEYWORD_MAX_LENGTH = 100;
    static final int TAG_MAX_LENGTH = 50;
    static final int MAX_PAGE = 999;

    private static final Pattern LEVEL_IN_RANGE = Pattern.compile("[1-5]");
    /** PHP {@code preg_match('/^[0-9]+$/')} 와 같다. {@code $} 가 끝 개행 앞에서도 맞으므로 끝 개행 하나를 허용한다(search.php:337). */
    private static final Pattern PAGE_DIGITS = Pattern.compile("[0-9]+\\n?");
    /** PHP {@code (int)"문자열"} 이 읽는 앞부분: 공백, 부호, 숫자, 소수부, 지수부. */
    private static final Pattern PHP_NUMERIC_PREFIX = Pattern.compile(
        "^[ \\t\\n\\r\\x0B\\f]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final Pattern PHP_INTEGER = Pattern.compile("[+-]?[0-9]+");

    private static final Sort DEFAULT_SORT = Sort.by(Order.desc("level"), Order.asc("id"));

    /**
     * 요청 파라미터에서 조건을 만든다. 같은 이름이 여러 번 오면 마지막 값을 쓴다(PHP {@code $_GET} 과 같다).
     */
    public static ItemSearchCriteria from(Map<String, List<String>> params) {
        String keyword = truncate(phpTrim(lastValue(params, "q")), KEYWORD_MAX_LENGTH);
        String unitCode = phpTrim(lastValue(params, "unit"));
        String tag = truncate(phpTrim(lastValue(params, "tag")), TAG_MAX_LENGTH);
        return new ItemSearchCriteria(
            emptyToNull(keyword),
            emptyToNull(unitCode),
            parseLevel(phpTrim(lastValue(params, "level"))),
            emptyToNull(tag),
            parseSort(lastValue(params, "sort"), lastValue(params, "dir")),
            parsePage(lastValue(params, "page")));
    }

    private static Integer parseLevel(String level) {
        if (level.isEmpty()) {
            return null;
        }
        if (LEVEL_IN_RANGE.matcher(level).matches()) {
            return Integer.valueOf(level);
        }
        // 범위 밖 값은 거부하지 않고 정수로 바꿔 그대로 비교한다(레거시 동작).
        return clampToInt(phpIntValue(level));
    }

    private static int parsePage(String pageRaw) {
        if (pageRaw == null || !PAGE_DIGITS.matcher(pageRaw).matches()) {
            return 1;
        }
        long page = phpIntValue(pageRaw);
        return (int) Math.max(1, Math.min(page, MAX_PAGE));
    }

    private static Sort parseSort(String sortRaw, String dirRaw) {
        String sort = phpTrim(sortRaw).toLowerCase(Locale.ROOT);
        String dir = phpTrim(dirRaw).toLowerCase(Locale.ROOT);
        boolean asc = "asc".equals(dir);
        boolean desc = "desc".equals(dir);
        return switch (sort) {
            case "id" -> Sort.by(desc ? Order.desc("id") : Order.asc("id"));
            case "title" -> Sort.by(desc ? Order.desc("title") : Order.asc("title"), Order.asc("id"));
            case "unit" -> Sort.by(
                desc ? Order.desc("unit.code") : Order.asc("unit.code"), Order.desc("level"), Order.asc("id"));
            case "level" -> Sort.by(asc ? Order.asc("level") : Order.desc("level"), Order.asc("id"));
            case "created" -> Sort.by(asc ? Order.asc("createdAt") : Order.desc("createdAt"), Order.asc("id"));
            default -> DEFAULT_SORT;
        };
    }

    private static String lastValue(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty() || values.get(values.size() - 1) == null) {
            return "";
        }
        return values.get(values.size() - 1);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /** PHP {@code trim} 이 지우는 문자만 지운다: 공백 · 탭 · 개행 · 널 · 수직탭. */
    private static String phpTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isPhpSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isPhpSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isPhpSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }

    /** PHP {@code mb_substr} 처럼 글자(코드포인트) 단위로 자른다. */
    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    /** PHP {@code (int)"문자열"}: 앞부분의 숫자만 읽고 없으면 0, 범위를 넘으면 long 한계로 자른다. */
    static long phpIntValue(String value) {
        Matcher matcher = PHP_NUMERIC_PREFIX.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        String number = matcher.group(1);
        if (PHP_INTEGER.matcher(number).matches()) {
            BigInteger big = new BigInteger(number.startsWith("+") ? number.substring(1) : number);
            return big.max(BigInteger.valueOf(Long.MIN_VALUE)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        }
        return (long) Double.parseDouble(number);
    }

    /** 난이도 컬럼은 작은 정수라 int 범위를 넘는 값은 어차피 일치하는 행이 없다. */
    private static int clampToInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(value, Integer.MAX_VALUE));
    }
}
