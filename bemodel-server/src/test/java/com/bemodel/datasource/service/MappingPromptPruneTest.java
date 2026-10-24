package com.bemodel.datasource.service;

import com.bemodel.datasource.entity.PhysicalColumn;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Term;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1-C/A 纯函数单测（免 Spring 上下文）：Top-K 预筛的命中保留/零分剔除/截断/术语针，
 * 批切分边界（空列/整除/余数/非法 size），蛇形拆词。
 */
class MappingPromptPruneTest {

    private static PhysicalColumn col(String name, String comment) {
        PhysicalColumn c = new PhysicalColumn();
        c.setColumnName(name);
        c.setColumnComment(comment);
        return c;
    }

    private static Concept concept(String code, String name) {
        Concept c = new Concept();
        c.setCode(code);
        c.setName(name);
        return c;
    }

    private static Attribute attr(String conceptCode, String attrCode, String attrName) {
        Attribute a = new Attribute();
        a.setConceptCode(conceptCode);
        a.setAttrCode(attrCode);
        a.setAttrName(attrName);
        return a;
    }

    // ---------- partition（F1-A 批切分） ----------

    @Test
    void 分批_空列零批_整除与余数批() {
        assertTrue(MappingService.partition(List.of(), 25).isEmpty(), "空列切出零批");
        assertEquals(List.of(2, 2), MappingService.partition(List.of(1, 2, 3, 4), 2)
                .stream().map(List::size).toList(), "整除批");
        assertEquals(List.of(2, 2, 1), MappingService.partition(List.of(1, 2, 3, 4, 5), 2)
                .stream().map(List::size).toList(), "余数批");
    }

    @Test
    void 分批_非法size视为整批不切() {
        List<Integer> items = List.of(1, 2, 3);
        assertEquals(List.of(items), MappingService.partition(items, 0));
        assertEquals(List.of(items), MappingService.partition(items, -1));
    }

    @Test
    void 分批_元素与顺序保真() {
        List<String> out = new ArrayList<>();
        MappingService.partition(List.of("a", "b", "c"), 2).forEach(out::addAll);
        assertEquals(List.of("a", "b", "c"), out);
    }

    // ---------- splitSnake（编码拆词） ----------

    @Test
    void 拆词_整体加拆词_单字符词剔除() {
        assertEquals(List.of("inp_visit", "inp", "visit"), MappingService.splitSnake("Demand"));
        assertEquals(List.of("a_b"), MappingService.splitSnake("A_B"), "单字符拆词不入针集（误命中噪声）");
        assertTrue(MappingService.splitSnake(null).isEmpty());
        assertTrue(MappingService.splitSnake("  ").isEmpty());
    }

    // ---------- topKConcepts（F1-C 预筛） ----------

    @Test
    void 预筛_零分概念剔除_命中概念保留_按分降序() {
        List<Concept> concepts = List.of(
                concept("VISIT", "就诊"),
                concept("DRUG", "药品"),
                concept("PATIENT", "联系人"));
        List<Attribute> attrs = List.of(
                attr("VISIT", "visit_id", "就诊流水号"),
                attr("PATIENT", "patient_id", "联系人编号"));
        List<PhysicalColumn> columns = List.of(
                col("patient_id", "联系人编号"),
                col("visit_sn", "就诊流水"));
        // DRUG 零分剔除；PATIENT 命中率 4/4=1.0 高于 VISIT 2/4=0.5。k 必须小于概念数才走计分路径
        List<Concept> picked = MappingService.topKConcepts(concepts, attrs, List.of(), columns, 2);
        assertEquals(List.of("PATIENT", "VISIT"), picked.stream().map(Concept::getCode).toList());
        assertFalse(picked.stream().anyMatch(c -> "DRUG".equals(c.getCode())), "零分概念不入提示词");
    }

    @Test
    void 预筛_K截断_术语别名可救回概念() {
        List<Concept> concepts = List.of(concept("A_X", "甲概念"), concept("B_X", "乙概念"));
        // B_X 只有术语别名「报销药」命中列注释，无编码/属性命中——术语针救回
        List<Term> terms = List.of(term("B_X", "报销药"));
        List<PhysicalColumn> columns = List.of(
                col("c1", "甲概念相关"),
                col("c2", "乙概念及报销药相关"));
        List<Concept> top1 = MappingService.topKConcepts(concepts, List.of(), terms, columns, 1);
        assertEquals(List.of("B_X"), top1.stream().map(Concept::getCode).toList(),
                "B_X 命中率 2/3≈0.67 > A_X 1/2=0.5，Top-1 截断后应留 B_X");
    }

    @Test
    void 预筛_K不触发时原样返回() {
        List<Concept> concepts = List.of(concept("A", "甲"), concept("B", "乙"));
        assertEquals(concepts, MappingService.topKConcepts(concepts, List.of(), List.of(),
                List.of(col("c", "无")), 0), "K<=0 视为不筛");
        assertEquals(concepts, MappingService.topKConcepts(concepts, List.of(), List.of(),
                List.of(col("c", "无")), 5), "概念数不超过 K 时不筛");
    }

    @Test
    void 预筛_注释大写缩写按小写命中() {
        // 列注释未同口径小写化时，注释里的大写缩写（CT/MRI）永远无法命中任何小写针——
        // 对抗评审 HIGH 确认缺陷的回归钉
        List<Concept> concepts = List.of(concept("A", "甲"), concept("B", "乙"));
        List<Term> terms = List.of(term("A", "MRI"));
        List<Concept> picked = MappingService.topKConcepts(concepts, List.of(), terms,
                List.of(col("c1", "有MRI影像")), 1);
        assertEquals(List.of("A"), picked.stream().map(Concept::getCode).toList(),
                "注释 MRI 经小写命中术语针 mri；乙概念零分剔除");
    }

    private static Term term(String conceptCode, String t) {
        Term x = new Term();
        x.setConceptCode(conceptCode);
        x.setTerm(t);
        return x;
    }
}
