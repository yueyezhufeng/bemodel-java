package com.bemodel.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 工业联系人员脱敏（P0②）纯单元测试：无 Spring 上下文依赖 */
class MaskingTest {

    @Test
    void 姓名打码_保留姓与末字_两字张星_单字星() {
        assertEquals("张*丰", Masking.maskName("张三丰"));
        assertEquals("张*", Masking.maskName("张三"));
        assertEquals("*", Masking.maskName("张"));
        assertEquals("*", Masking.maskName(null));
        assertEquals("*", Masking.maskName(""));
        // 已打码值再打码保持稳定（幂等）
        assertEquals("张*丰", Masking.maskName("张*丰"));
    }

    @Test
    void 姓名类键识别_物理列与本体属性两种形态() {
        assertTrue(Masking.isNameKey("contact_name"));
        assertTrue(Masking.isNameKey("EMPLOYEE_NAME"));
        assertTrue(Masking.isNameKey("operator_name"));
        assertTrue(Masking.isNameKey("姓名"));
        assertTrue(Masking.isNameKey("联系人姓名"));
        assertFalse(Masking.isNameKey("lot_id"));      // 业务键保留
        assertFalse(Masking.isNameKey("demand_id"));   // 业务键保留
        assertFalse(Masking.isNameKey("item_name"));
        assertFalse(Masking.isNameKey(null));
    }

    @Test
    void 行脱敏_只动姓名键_业务键与空值原样() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lot_id", "LOT2024001");
        row.put("contact_name", "张三丰");
        row.put("amount", "120.50");

        Map<String, Object> masked = Masking.maskRow(row);

        assertEquals("张*丰", masked.get("contact_name"));
        assertEquals("LOT2024001", masked.get("lot_id")); // 业务键保留
        assertEquals("120.50", masked.get("amount"));
        assertEquals(3, masked.size());
        // 原行不被修改（副本语义）
        assertEquals("张三丰", row.get("contact_name"));
    }

    @Test
    void 行脱敏_姓名为空值不产出星号噪声() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("contact_name", null);
        Map<String, Object> masked = Masking.maskRow(row);
        assertNull(masked.get("contact_name"));
    }

    @Test
    void 行列表脱敏_逐行副本_行数不变() {
        List<Map<String, Object>> rows = List.of(
                Map.of("contact_name", "张三丰", "lot_id", "A1"),
                Map.of("operator_name", "李四", "lot_id", "A2"));
        List<Map<String, Object>> masked = Masking.maskRows(rows);
        assertEquals(2, masked.size());
        assertEquals("张*丰", masked.get(0).get("contact_name"));
        assertEquals("李*", masked.get(1).get("operator_name"));
        assertEquals("A1", masked.get(0).get("lot_id"));
    }
}
