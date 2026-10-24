package com.bemodel.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.knowledge.entity.Knowledge;
import com.bemodel.knowledge.mapper.DocChunkMapper;
import com.bemodel.knowledge.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 工业知识表结构：保留FULLTEXT检索，不加载旧应用种子 */
@SpringBootTest
class KnowledgeSchemaTest {

    @Autowired
    private KnowledgeMapper knowledgeMapper;
    @Autowired
    private DocChunkMapper docChunkMapper;

    @Test
    void knowledgeTablesShouldExistWithoutLegacySeeds() {
        Long seeds = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .likeRight(Knowledge::getCode, "CS_RULE_")
                .or()
                .likeRight(Knowledge::getCode, "RECON_ATTR_"));
        assertEquals(0L, seeds, "工业基线不播种旧应用话术或医疗对账条目");
    }

    @Test
    void fulltextQueryShouldWorkOnEmptyTable() {
        // FULLTEXT ngram 冒烟：空表上布尔检索不炸、返回空集（索引创建成功即视为通过）
        assertDoesNotThrow(() -> docChunkMapper.fulltextSearch("供方", 5));
    }
}
