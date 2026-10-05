package com.bemodel.knowledge;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 原始问句 → FULLTEXT BOOLEAN MODE 查询串（纯函数，无状态）。
 *
 * <p>ngram 布尔模式会把整串问句转成短语搜索（要求片段连续出现原句字样），
 * 自然短语（如「探视制度」）因此零命中。这里把问句预切成 bigram 可选词项：
 * 连续中文段滑窗取二字，英文数字段整词一项（统一小写）；运算符/标点/控制符
 * 一律作分隔符，原样进查询串会改写布尔语义。命中排序交给既有的
 * {@code ORDER BY MATCH...AGAINST DESC}——谁在更多词项上命中谁靠前。
 *
 * <p>词项封顶 {@value #MAX_TOKENS} 个（超长问句截断，先到先得）；被分隔的单个
 * 中文字剥掉（ngram_token_size=2 单字必空）。返回空串时调用方应跳过关键词路。
 */
public final class FulltextQuery {

    private static final int MAX_TOKENS = 24;

    private FulltextQuery() {
    }

    public static String build(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Set<String> tokens = new LinkedHashSet<>();
        int i = 0;
        int len = raw.length();
        while (i < len && tokens.size() < MAX_TOKENS) {
            char c = raw.charAt(i);
            if (isAsciiWord(c)) {
                int j = i;
                while (j < len && isAsciiWord(raw.charAt(j))) {
                    j++;
                }
                tokens.add(raw.substring(i, j).toLowerCase());
                i = j;
            } else if (isCjk(c)) {
                int j = i;
                while (j < len && isCjk(raw.charAt(j))) {
                    j++;
                }
                for (int k = i; k + 1 < j && tokens.size() < MAX_TOKENS; k++) {
                    tokens.add(raw.substring(k, k + 2));
                }
                i = j;
            } else {
                i++;
            }
        }
        return String.join(" ", tokens);
    }

    /** CJK 统一表意文字基本区；中文标点（U+3000/U+FF00 区）不在内，天然作分隔符 */
    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
