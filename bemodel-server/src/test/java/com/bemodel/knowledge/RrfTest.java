package com.bemodel.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RRF 融合 golden：score(d) = Σ 1/(60 + rank)，rank 从 1 起 */
class RrfTest {

    @Test
    void dualListFusionShouldRankByReciprocalSum() {
        // a=1/61≈0.0164；b=1/62+1/61≈0.0325；c=1/63≈0.0159；x=1/62≈0.0161 → [b,a,x,c]
        List<String> fused = Rrf.fuse(List.of(List.of("a", "b", "c"), List.of("b", "x")), 60, 5);
        assertEquals(List.of("b", "a", "x", "c"), fused);
    }

    @Test
    void singleListShouldKeepOrderTruncated() {
        List<String> fused = Rrf.fuse(List.of(List.of("p", "q", "r", "s")), 60, 2);
        assertEquals(List.of("p", "q"), fused);
    }

    @Test
    void emptyAndMissingListsShouldDegrade() {
        assertEquals(List.of(), Rrf.fuse(List.of(), 60, 5));
        assertEquals(List.of(), Rrf.fuse(List.of(List.of()), 60, 5));
    }

    @Test
    void topNSmallerThanUnionShouldTruncate() {
        List<String> fused = Rrf.fuse(List.of(List.of("a", "b"), List.of("c", "d")), 60, 3);
        assertEquals(3, fused.size());
        assertEquals("a", fused.get(0));
    }
}
