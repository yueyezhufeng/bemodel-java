package com.bemodel.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 向量解析与余弦相似度纯函数：embedding JSON 数组字符串 ⇄ double[]；异常一律降级不抛 */
public final class CosineUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CosineUtil() {
    }

    /** 解析向量化结果（如 "[0.1,0.2,…]"）；空/非法/空数组返回 null（调用方跳过该片段） */
    public static double[] parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            double[] v = MAPPER.readValue(json, double[].class);
            return v.length == 0 ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 余弦相似度；维度不一致或零向量返回 0（降级为不相关） */
    public static double similarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
