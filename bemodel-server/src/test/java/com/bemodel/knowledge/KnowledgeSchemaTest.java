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

/** 知识双轨表结构与种子冒烟：四条迁移落地后表存在、FULLTEXT 可查、种子条目可按 code 读 */
@SpringBootTest
class KnowledgeSchemaTest {

    @Autowired
    private KnowledgeMapper knowledgeMapper;
    @Autowired
    private DocChunkMapper docChunkMapper;

    @Test
    void knowledgeTablesShouldExistWithTenSeeds() {
        Long seeds = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .likeRight(Knowledge::getCode, "CS_RULE_")
                .or()
                .likeRight(Knowledge::getCode, "RECON_ATTR_"));
        assertEquals(10L, seeds, "V41 应落 10 条种子条目（前缀过滤避免与其他测试新增条目串数）");
    }

    @Test
    void fulltextQueryShouldWorkOnEmptyTable() {
        // FULLTEXT ngram 冒烟：空表上布尔检索不炸、返回空集（索引创建成功即视为通过）
        assertDoesNotThrow(() -> docChunkMapper.fulltextSearch("探视", 5));
    }
}
