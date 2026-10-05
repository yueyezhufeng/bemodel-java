package com.bemodel.cs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景数据源清单纯逻辑:空清单=不过滤(红线);启用后按编码精确匹配(trim 容忍),空白编码不在场。
 */
class SemanticScenePropertiesTest {

    @Test
    void emptyListShouldDisableFiltering() {
        SemanticSceneProperties p = new SemanticSceneProperties();
        assertFalse(p.filtering(), "空清单 = 不过滤");
        assertTrue(p.inScene("DS_HIS"), "不过滤时任何数据源都在场(旧行为)");
        assertTrue(p.inScene(null), "不过滤时 null 也在场(旧行为)");
    }

    @Test
    void activeListShouldMatchExactCodeWithTrim() {
        SemanticSceneProperties p = new SemanticSceneProperties();
        p.setSceneDs(List.of("DS_HIS_INP", "DS_MEDREC"));
        assertTrue(p.filtering());
        assertTrue(p.inScene("DS_HIS_INP"));
        assertTrue(p.inScene(" DS_HIS_INP "), "前后空白容忍");
        assertFalse(p.inScene("DS_HIS_INP2"), "不做前缀匹配");
        assertFalse(p.inScene("DS_CHARGE"), "清单外不在场");
        assertFalse(p.inScene(null));
        assertFalse(p.inScene("  "));
        p.setSceneDs(List.of(" DS_HIS_INP "));
        assertTrue(p.inScene("DS_HIS_INP"), "清单条目带空白同样容忍(两侧 trim)");
        assertFalse(p.inScene("DS_HIS_INP2"));
    }

    @Test
    void nullListShouldDisableFiltering() {
        SemanticSceneProperties p = new SemanticSceneProperties();
        p.setSceneDs(null);
        assertFalse(p.filtering(), "null 与空清单同义 = 不过滤");
    }
}
