package com.bemodel.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）合并纯函数：score(d) = Σ 1/(k + rank_i(d))，rank 从 1 起。
 * 确定性合并（LLM 不参与排序）；同分按先出现序稳定排序（LinkedHashMap 插入序 + 稳定归并）。
 */
public final class Rrf {

    private Rrf() {
    }

    /** 多路 ranked id 合并：返回按融合分降序的 id 列表，截 topN；单路内 id 不重复（调用方保证） */
    public static List<String> fuse(List<List<String>> rankedLists, int k, int topN) {
        Map<String, Double> score = new LinkedHashMap<>();
        for (List<String> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                score.merge(list.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        List<String> ids = new ArrayList<>(score.keySet());
        ids.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return ids.subList(0, Math.min(topN, ids.size()));
    }
}
