package com.bemodel.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档切分 golden：标题路径/目标字数/相邻重叠/中英文混排/短文档单片段 */
class ChunkerTest {

    @Test
    void shortDocShouldYieldSingleChunkWithHeading() {
        String text = "# 缴费管理规定\n\n缴费是发药的前置环节，先药后费属违规。";
        List<Chunker.Chunk> chunks = Chunker.split(text, 500, 50);
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).seq());
        assertEquals("缴费管理规定", chunks.get(0).heading());
        assertTrue(chunks.get(0).content().contains("缴费是发药的前置环节"));
    }

    @Test
    void nestedHeadingsShouldBuildPathAndSplitChunks() {
        String text = "# 缴费管理\n\n第一段内容。\n\n## 退费流程\n\n退费段落内容。";
        List<Chunker.Chunk> chunks = Chunker.split(text, 500, 50);
        assertEquals(2, chunks.size());
        assertEquals("缴费管理", chunks.get(0).heading());
        assertEquals("缴费管理/退费流程", chunks.get(1).heading());
        assertTrue(chunks.get(1).content().contains("退费段落内容"));
    }

    @Test
    void oversizedParagraphShouldSlideWindowWithOverlap() {
        String body = "长".repeat(1200);
        List<Chunker.Chunk> chunks = Chunker.split(body, 500, 50);
        assertTrue(chunks.size() >= 3, "1200 字按 500/50 应切出至少 3 片: " + chunks.size());
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).content();
            String cur = chunks.get(i).content();
            int overlapLen = Math.min(50, Math.min(prev.length(), cur.length()));
            assertEquals(prev.substring(prev.length() - overlapLen),
                    cur.substring(0, overlapLen), "相邻片段应重叠 " + overlapLen + " 字");
        }
    }

    @Test
    void mixedChineseEnglishShouldChunkCleanly() {
        String text = "# Policy\n\nThe patient 缴费记录 must match dispense 记录 exactly.";
        List<Chunker.Chunk> chunks = Chunker.split(text, 500, 50);
        assertEquals(1, chunks.size());
        assertEquals("Policy", chunks.get(0).heading());
        assertTrue(chunks.get(0).content().contains("The patient 缴费记录"));
    }

    @Test
    void blankOrNullShouldReturnEmpty() {
        assertTrue(Chunker.split(null, 500, 50).isEmpty());
        assertTrue(Chunker.split("   \n\n  ", 500, 50).isEmpty());
    }
}
