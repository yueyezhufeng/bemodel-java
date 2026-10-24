package com.bemodel.instance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.common.Masking;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 实例浏览器：概念 → 映射 → 物理库实时反查。
 * 把各产品库中割裂的物理行，按概念的标准属性和值字典口径还原为「本体实例」，
 * 证明本体不是纸面建模，而是能直接消费真实数据的语义层。
 */
@Service
@RequiredArgsConstructor
public class InstanceService {

    private final ConceptMapper conceptMapper;
    private final AttributeMapper attributeMapper;
    private final MappingMapper mappingMapper;
    private final DatasourceService datasourceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> instances(String conceptCode, String tableName, int pageNum, int pageSize) {
        Concept concept = conceptMapper.selectOne(
                new LambdaQueryWrapper<Concept>().eq(Concept::getCode, conceptCode));
        if (concept == null) {
            throw new BizException("概念不存在: " + conceptCode);
        }
        // 废弃概念隔离（维护治理加固 H3）：显式拒绝而非静默空结果，
        // 废弃概念的物理表仍在，投影出来会让人误以为口径仍生效
        if ("DEPRECATED".equals(concept.getStatus())) {
            throw new BizException("概念已废弃: " + conceptCode + "，仅供追溯，不再投影实例数据");
        }
        List<Attribute> attrs = attributeMapper.selectList(
                new LambdaQueryWrapper<Attribute>().eq(Attribute::getConceptCode, conceptCode)
                        .orderByAsc(Attribute::getSort));
        Map<String, String> attrNameByCode = attrs.stream()
                .collect(Collectors.toMap(Attribute::getAttrCode, Attribute::getAttrName, (a, b) -> a));

        List<Mapping> mappings = mappingMapper.selectList(
                new LambdaQueryWrapper<Mapping>().eq(Mapping::getConceptCode, conceptCode)
                        .eq(Mapping::getStatus, "ACTIVE")); // 生命周期（V30）：实例投影只消费生效映射
        // 按数据源+物理表分组，映射列多的表排前面（主载体表优先）
        Map<String, List<Mapping>> byTable = mappings.stream().collect(Collectors.groupingBy(
                m -> m.getDsCode() + "@" + m.getTableName(), LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<String, List<Mapping>>> sorted = byTable.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size()).toList();

        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map.Entry<String, List<Mapping>> entry : sorted) {
            String[] parts = entry.getKey().split("@");
            String dsCode = parts[0];
            String table = parts[1];
            List<Mapping> cols = entry.getValue();

            String selectCols = cols.stream().map(Mapping::getColumnName).distinct()
                    .collect(Collectors.joining(", "));
            JdbcTemplate jdbc = datasourceService.jdbc(dsCode);
            // 指定来源表时才分页取数；未指定时各表只取首页（概览）
            boolean pageThis = tableName == null || tableName.isBlank() || tableName.equals(table);
            int offset = (pageNum - 1) * pageSize;
            List<Map<String, Object>> rows = pageThis
                    ? jdbc.queryForList("SELECT " + selectCols + " FROM " + table + " LIMIT " + pageSize + " OFFSET " + offset)
                    : jdbc.queryForList("SELECT " + selectCols + " FROM " + table + " LIMIT " + pageSize);
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);

            List<Map<String, Object>> projected = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> inst = new LinkedHashMap<>();
                for (Mapping m : cols) {
                    Object raw = row.get(m.getColumnName());
                    String attrKey = attrNameByCode.getOrDefault(m.getAttrCode(), m.getAttrCode());
                    Object value = decodeValue(m, raw);
                    // 联系人或员工姓名在投影出口打码，批次与订单业务键保留
                    if ((Masking.isNameKey(m.getColumnName()) || Masking.isNameKey(attrKey))
                            && !"-".equals(value)) {
                        value = Masking.maskName(String.valueOf(value));
                    }
                    inst.put(attrKey, value);
                }
                projected.add(inst);
            }
            sources.add(Map.of(
                    "dsCode", dsCode,
                    "tableName", table,
                    "totalRows", total == null ? 0 : total,
                    "instances", projected));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conceptCode", concept.getCode());
        result.put("conceptName", concept.getName());
        result.put("definition", concept.getDefinition());
        result.put("sourceCount", sources.size());
        result.put("sources", sources);
        return result;
    }

    private String decodeValue(Mapping m, Object raw) {
        if (raw == null) {
            return "-";
        }
        if (m.getValueMap() == null) {
            return String.valueOf(raw);
        }
        try {
            JsonNode node = objectMapper.readTree(m.getValueMap());
            JsonNode hit = node.get(String.valueOf(raw));
            return hit == null ? String.valueOf(raw) : hit.asText();
        } catch (Exception e) {
            return String.valueOf(raw);
        }
    }
}
