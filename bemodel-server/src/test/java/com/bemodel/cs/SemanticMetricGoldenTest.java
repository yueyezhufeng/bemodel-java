package com.bemodel.cs;

import com.bemodel.ontology.entity.Metric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 口径卡预筛纯函数:CJK 二元组重叠 >=2 入围,上限 5;零 LLM。
 * 「9月出院多少人」应命中出院口径卡、不碰费用口径卡——治 15 vs 153 断层的预筛地基。
 */
class SemanticMetricGoldenTest {

    private Metric card(String code, String name, String definition) {
        Metric m = new Metric();
        m.setMetricCode(code);
        m.setName(name);
        m.setDefinition(definition);
        m.setDsCode("DS_HIS");
        m.setProbeSql("SELECT 1");
        return m;
    }

    @Test
    void bigramsShouldSplitCjkOnlyAndIgnoreAscii() {
        Set<String> b = SemanticQaService.cjkBigrams("9月出院多少人");
        // 注:brief 原期望 {月出,出院,院多,多人} 与输入自相矛盾——「多人」不连续(原文为 多/少/人);
        // 实现按 brief 原文(剥非 CJK 后窗口式)产 {月出,出院,院多,多少,少人},此处按实现的真实输出钉死
        assertEquals(Set.of("月出", "出院", "院多", "多少", "少人"), b, "ASCII 数字不进二元组");
        assertTrue(SemanticQaService.cjkBigrams("room 101").isEmpty(), "纯 ASCII 无 CJK 二元组");
    }

    @Test
    void prefilterShouldHitDischargeCardsNotFeeCardsAndCapFive() {
        Metric dis1 = card("M_DIS1", "9月出院人数(病案口径)", "出院且首页已交的住院人次");
        Metric dis2 = card("M_DIS2", "9月出院人数(结算口径)", "出院且结算完成的住院人次");
        Metric fee = card("M_FEE", "9月住院费用(结算口径)", "出院患者的结算总费用");
        List<Metric> hit = SemanticQaService.prefilterMetricCards("9月出院多少人", List.of(dis1, dis2, fee));
        assertEquals(2, hit.size());
        assertTrue(hit.stream().noneMatch(m -> m.getMetricCode().equals("M_FEE")), "费用卡不应入围");

        List<Metric> many = List.of(dis1, dis2, dis1, dis1, dis1, dis1);
        assertEquals(5, SemanticQaService.prefilterMetricCards("9月出院多少人", many).size(), "入围上限 5");
        assertTrue(SemanticQaService.prefilterMetricCards("会议室几点开门", List.of(dis1, fee)).isEmpty(),
                "零重叠返回空表(prompt 保持原样)");
    }
}
