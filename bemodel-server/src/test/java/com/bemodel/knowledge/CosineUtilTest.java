package com.bemodel.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 向量解析与余弦相似度 golden */
class CosineUtilTest {

    @Test
    void parseShouldReadJsonArrayAndRejectGarbage() {
        double[] v = CosineUtil.parse("[0.1,0.2,0.3]");
        assertEquals(3, v.length);
        assertEquals(0.1, v[0], 1e-9);
        assertNull(CosineUtil.parse(null));
        assertNull(CosineUtil.parse(""));
        assertNull(CosineUtil.parse("not-json"));
        assertNull(CosineUtil.parse("[]"));
    }

    @Test
    void similarityShouldBeOneForIdenticalVectors() {
        double[] v = {0.2, 0.4, 0.6};
        assertEquals(1.0, CosineUtil.similarity(v, v), 1e-9);
    }

    @Test
    void similarityShouldBeZeroForMismatchedDimensionOrZeroVector() {
        assertEquals(0.0, CosineUtil.similarity(new double[]{1, 2}, new double[]{1, 2, 3}));
        assertEquals(0.0, CosineUtil.similarity(new double[]{0, 0}, new double[]{1, 1}));
        assertEquals(0.0, CosineUtil.similarity(null, new double[]{1}));
    }

    @Test
    void similarityShouldBeNegativeForOppositeVectors() {
        assertEquals(-1.0, CosineUtil.similarity(new double[]{1, 0}, new double[]{-1, 0}), 1e-9);
    }
}
