package com.bemodel.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** FulltextQuery golden：原始问句 → BOOLEAN MODE 查询串（bigram 可选词项，ngram_token_size=2） */
class FulltextQueryTest {

    @Test
    void blankAndPunctuationOnlyShouldReturnEmpty() {
        assertEquals("", FulltextQuery.build(null));
        assertEquals("", FulltextQuery.build(""));
        assertEquals("", FulltextQuery.build("   "));
        assertEquals("", FulltextQuery.build("？？？"));
        assertEquals("", FulltextQuery.build("，。！？"));
    }

    @Test
    void booleanOperatorsShouldActAsSeparators() {
        // + - > < ( ) ~ * " @ 是 BOOLEAN MODE 运算符，必须当分隔符，原样进查询串会改语义
        assertEquals("探视 制度", FulltextQuery.build("探视+制度"));
        assertEquals("探视 制度", FulltextQuery.build("探视 -制度"));
        assertEquals(FulltextQuery.build("探视制度"), FulltextQuery.build("探视制度"));
    }

    @Test
    void cjkRunShouldSlideToBigrams() {
        assertEquals("探视", FulltextQuery.build("探视"));
        assertEquals("探视 视制 制度", FulltextQuery.build("探视制度"));
        assertEquals("病区 区探 探视 视时 时间", FulltextQuery.build("病区探视时间"));
    }

    @Test
    void asciiRunShouldBeWholeLowercaseToken() {
        assertEquals("phi", FulltextQuery.build("PHI"));
        assertEquals("探视 abc 制度", FulltextQuery.build("探视ABC制度"));
    }

    @Test
    void duplicatesShouldCollapseKeepingFirstOrder() {
        assertEquals("探视 视制 制度", FulltextQuery.build("探视制度，探视"));
        assertEquals("探视 视探", FulltextQuery.build("探视探视"));
    }

    @Test
    void singleCharSegmentShouldBeDropped() {
        // ngram_token_size=2，被分隔的单字段必空，剥掉省一次 SQL；连续多字不受影响
        assertEquals("", FulltextQuery.build("药"));
        assertEquals("探视", FulltextQuery.build("药，探视"));
        assertEquals("药探 探视", FulltextQuery.build("药探视"));
    }

    @Test
    void punctuationShouldSeparateSegments() {
        assertEquals("探视 制度", FulltextQuery.build("探视，制度？"));
        assertEquals("病区 区探 探视 制度", FulltextQuery.build("病区探视，制度"));
    }

    @Test
    void longQueryShouldCapAt24Tokens() {
        // 30 个互异汉字 → 29 个互异 bigram，封顶 24（重复字串会先被去重，测不出封顶）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append((char) (0x4E00 + i));
        }
        assertEquals(24, FulltextQuery.build(sb.toString()).split(" ").length);
    }
}
