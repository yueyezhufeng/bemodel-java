package com.bemodel.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工业联系人与员工身份脱敏：姓名类身份字段在一切出口统一打码——
 * API 响应行、持久化的标题/载荷、送外部 LLM 的提示词，三类出口同一口径。
 * 口径：业务键（lot_id / demand_id 等单据号）保留用于关联与下钻；
 * 身份字段（姓名类）一律打码，已知值域之外的姓名类键用 isNameKey 显式登记。
 */
public final class Masking {

    /** 姓名类字段键（小写）；联系人与员工字段显式登记，企业名称与业务编号保持原样 */
    private static final Set<String> NAME_KEYS = Set.of("contact_name", "employee_name", "operator_name", "姓名");

    private Masking() {
    }

    /** 姓名脱敏：张*三（两字名 张*；单字/空 → *） */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "*";
        }
        if (name.length() == 1) {
            return "*";
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*" + name.substring(name.length() - 1);
    }

    /** 键是否姓名类字段；属性名含"姓名"也视为命中（本体属性名/物理列名两种形态） */
    public static boolean isNameKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        return NAME_KEYS.contains(lower) || lower.contains("姓名");
    }

    /** 值是否姓名类字段的非空值 */
    public static boolean isNameValue(String key, Object value) {
        return value != null && isNameKey(key);
    }

    /** 单行脱敏：返回副本，姓名类键的值打码，其余原样（含业务键） */
    public static Map<String, Object> maskRow(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (isNameValue(e.getKey(), e.getValue())) {
                copy.put(e.getKey(), maskName(String.valueOf(e.getValue())));
            }
        }
        return copy;
    }

    /** 行列表脱敏：逐行副本打码，行数与键集不变 */
    public static List<Map<String, Object>> maskRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        return rows.stream().map(Masking::maskRow).collect(Collectors.toList());
    }
}
