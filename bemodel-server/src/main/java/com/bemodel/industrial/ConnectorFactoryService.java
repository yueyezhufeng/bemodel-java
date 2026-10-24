package com.bemodel.industrial;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Versioned, read-only integration contracts. These never enter the JDBC execution mapping table. */
@Service
public class ConnectorFactoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ArrayNode templates;

    public ConnectorFactoryService(JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.jdbc = jdbc;
        this.json = json;
        try (var input = new ClassPathResource("industrial/connectors.json").getInputStream()) {
            templates = (ArrayNode) json.readTree(input).path("templates");
        }
    }

    public JsonNode templates() { return templates.deepCopy(); }

    @Transactional
    public Map<String, Integer> initialize() {
        writer();
        return seed();
    }

    /** Called once at startup after Flyway. INSERT IGNORE preserves every existing edited document. */
    @Transactional
    public Map<String, Integer> bootstrap() { return seed(); }

    private Map<String, Integer> seed() {
        int created = 0;
        for (JsonNode template : templates) {
            int n = jdbc.update("INSERT IGNORE INTO fp_connector_config(code, document, updated_by) VALUES (?, ?, ?)",
                    template.path("code").asText(), encode(template), "baseline-initializer");
            if (n == 1) {
                audit(template.path("code").asText(), "INITIALIZE", 1, template);
                created++;
            }
        }
        return Map.of("created", created, "preserved", templates.size() - created);
    }

    public List<ObjectNode> list() {
        return jdbc.query("SELECT * FROM fp_connector_config ORDER BY code", (r, i) -> {
            ObjectNode result = (ObjectNode) parse(r.getString("document"));
            result.put("version", r.getInt("version"));
            Integer validated = (Integer) r.getObject("validated_version");
            result.put("contractValidated", validated != null && validated == r.getInt("version"));
            result.set("validation", r.getString("validation") == null ? json.nullNode() : parse(r.getString("validation")));
            result.put("updatedBy", r.getString("updated_by"));
            result.put("updatedAt", r.getTimestamp("updated_at").toLocalDateTime().toString());
            result.put("runtimeEnabled", false);
            result.put("liveConnectionTested", false);
            return result;
        });
    }

    public ObjectNode get(String code) {
        return list().stream().filter(d -> d.path("code").asText().equals(code)).findFirst()
                .orElseThrow(() -> new BizException("连接器不存在: " + code));
    }

    @Transactional
    public ObjectNode create(JsonNode request) {
        writer();
        String code = request.path("code").asText();
        if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) throw new BizException("连接器编码使用3–64位大写字母、数字或下划线");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM fp_connector_config", Integer.class) >= 100)
            throw new BizException("本机演示最多保留100个连接器配置");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM fp_connector_config WHERE code=?", Integer.class, code) > 0)
            throw new BizException("连接器编码已存在");
        JsonNode template = template(request.path("templateCode").asText());
        ObjectNode doc = template.deepCopy();
        doc.put("code", code);
        String name = request.path("name").asText().trim();
        if (name.isEmpty() || name.length() > 100) throw new BizException("请输入100字以内的连接器名称");
        doc.put("name", name);
        jdbc.update("INSERT INTO fp_connector_config(code,document,updated_by) VALUES (?,?,?)", code, encode(doc), CurrentUser.username());
        audit(code, "CREATE", 1, doc);
        return get(code);
    }

    @Transactional
    public ObjectNode save(String code, JsonNode patch) {
        writer();
        ObjectNode before = get(code);
        ObjectNode after = normalized(before, patch);
        int version = patch.path("version").asInt(-1);
        int n = jdbc.update("UPDATE fp_connector_config SET document=?, version=version+1, validated_version=NULL, validation=NULL, updated_by=?, updated_at=CURRENT_TIMESTAMP(6) WHERE code=? AND version=?",
                encode(after), CurrentUser.username(), code, version);
        if (n != 1) throw new BizException("配置已被其他人更新，请刷新后再保存");
        ObjectNode detail = json.createObjectNode();
        detail.set("before", clean(before)); detail.set("after", after);
        audit(code, "UPDATE", version + 1, detail);
        return get(code);
    }

    /** Validate a saved version only. No network, SQL on source systems, sync or business writes. */
    @Transactional
    public ObjectNode validate(String code, JsonNode request) {
        writer();
        ObjectNode doc = get(code);
        int version = request.path("version").asInt(-1);
        if (version != doc.path("version").asInt()) throw new BizException("配置版本已变化，请刷新后重新校验");
        Map<String,String> targetTypes = new HashMap<>();
        jdbc.query("SELECT CONCAT(a.concept_code,'.',a.attr_code), a.data_type FROM bm_attribute a JOIN bm_concept c ON c.code=a.concept_code WHERE c.status <> 'DEPRECATED'", r -> {
            targetTypes.put(r.getString(1),r.getString(2));
        });
        ObjectNode result = check(doc, targetTypes.keySet());
        ArrayNode issues = (ArrayNode) result.path("issues");
        Map<String,String> modelTypes = Map.of("integer","NUMBER","boolean","ENUM","enum","ENUM","date","DATE","datetime","DATE","date_or_datetime","DATE","string","STRING","array","STRING","object","STRING");
        for (JsonNode m : doc.path("mappings")) {
            String key = m.path("entity").asText()+"."+m.path("target").asText();
            if (targetTypes.containsKey(key) && !Objects.equals(targetTypes.get(key),modelTypes.get(m.path("type").asText())))
                issue(issues,"error",key,"当前本体属性类型已变化，请核对模型与映射契约");
        }
        long errors = 0; for (JsonNode i : issues) if (i.path("severity").asText().equals("error")) errors++;
        result.put("errors",errors); result.put("valid",errors==0);
        int n = jdbc.update("UPDATE fp_connector_config SET validated_version=?, validation=? WHERE code=? AND version=?",
                result.path("valid").asBoolean() ? version : null, encode(result), code, version);
        if (n != 1) throw new BizException("校验期间配置已变化，请刷新后重新校验");
        audit(code, "VALIDATE", version, result);
        return get(code);
    }

    /** Samples remain in request memory. Logs contain no row payload or field values. */
    @Transactional
    public ObjectNode sample(String code, JsonNode request) {
        writer();
        ObjectNode doc = get(code);
        if (request.path("version").asInt(-1) != doc.path("version").asInt()) throw new BizException("配置版本已变化，请刷新");
        String entity = request.path("entity").asText();
        List<JsonNode> maps = new ArrayList<>();
        for (JsonNode m : doc.path("mappings")) if (m.path("entity").asText().equals(entity) && m.path("enabled").asBoolean()) maps.add(m);
        if (maps.isEmpty()) throw new BizException("请选择当前连接器中的对象");
        JsonNode rows = request.path("rows");
        if (!rows.isArray() || rows.size() < 1 || rows.size() > 100 || encode(rows).length() > 500000)
            throw new BizException("样本需为1–100条源记录的JSON数组，且小于500KB");
        ArrayNode issues = json.createArrayNode();
        Set<String> identities = new HashSet<>();
        int index = 0;
        for (JsonNode row : rows) {
            index++;
            String at = entity + "[" + index + "]";
            if (!row.isObject()) { issue(issues,"error",at,"源记录必须为对象"); continue; }
            List<String> keys = new ArrayList<>();
            if (entity.equals("EvidenceVersion")) {
                if (row.path("_evidence_id").asText().isBlank()) issue(issues,"error",at,"版本身份缺少 _evidence_id，不能只以版本号去重");
                keys.add(row.path("_evidence_id").asText());
            }
            for (JsonNode m : maps) {
                String field = m.path("field").asText();
                String target = at + "." + m.path("target").asText();
                if (field.isBlank()) { issue(issues,"error",target,"未配置源字段，不能验证"); continue; }
                JsonNode value = row.get(field);
                if (value == null || value.isNull()) {
                    issue(issues, m.path("required").asBoolean() || m.path("key").asBoolean() ? "error" : "warning", target,"源值缺失，保留 unknown");
                    continue;
                }
                if (value.isTextual() && value.asText().isBlank() && (m.path("key").asBoolean() || m.path("required").asBoolean())) issue(issues,"error",target,"身份或必填字段不能为空字符串");
                if (!matches(value,m.path("type").asText())) issue(issues,"error",target,"源值类型不匹配；不自动转换或补零");
                if (m.path("key").asBoolean()) keys.add(value.toString());
            }
            if (!keys.isEmpty() && !identities.add(encode(json.valueToTree(keys)))) issue(issues,"error",at,"样本内身份重复");
        }
        long errors = 0; for (JsonNode i : issues) if (i.path("severity").asText().equals("error")) errors++;
        ObjectNode result = json.createObjectNode();
        result.put("valid", errors == 0); result.put("errors", errors); result.put("rows",rows.size()); result.put("entity",entity);
        result.put("version",doc.path("version").asInt()); result.set("issues",issues);
        result.put("scope","仅所提交样本的字段存在性、类型和样本内身份检查；未执行取数、全量对账或发布，样本不落库");
        audit(code,"SAMPLE_CHECK",doc.path("version").asInt(),result);
        return result;
    }

    static boolean matches(JsonNode value, String type) {
        return switch (type) {
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "string", "enum" -> value.isTextual();
            case "date", "datetime", "date_or_datetime" -> {
                if (!value.isTextual()) yield false;
                try {
                    if (type.equals("date") || type.equals("date_or_datetime") && value.asText().length() == 10)
                        java.time.LocalDate.parse(value.asText());
                    else java.time.OffsetDateTime.parse(value.asText());
                    yield true;
                } catch (java.time.DateTimeException e) { yield false; }
            }
            default -> false;
        };
    }

    public List<JsonNode> logs(String code) {
        get(code);
        return jdbc.query("SELECT id,operation,version,actor,detail,created_at FROM fp_connector_log WHERE connector_code=? ORDER BY id DESC LIMIT 30", (r,i) -> {
            ObjectNode log = json.createObjectNode();
            log.put("id", r.getLong("id")); log.put("operation", r.getString("operation")); log.put("version", r.getInt("version"));
            log.put("actor", r.getString("actor")); log.put("at", r.getTimestamp("created_at").toLocalDateTime().toString());
            log.set("detail", parse(r.getString("detail"))); return (JsonNode) log;
        }, code);
    }

    ObjectNode normalized(ObjectNode before, JsonNode patch) {
        ObjectNode result = clean(before);
        String name = patch.path("name").asText().trim();
        if (name.isEmpty() || name.length() > 100) throw new BizException("名称不能为空，且不超过100字");
        result.put("name", name);
        JsonNode config = patch.path("config");
        if (!config.isObject()) throw new BizException("连接配置格式错误");
        Set<String> allowed = new HashSet<>(); before.path("config").fieldNames().forEachRemaining(allowed::add);
        config.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw new BizException("不支持的配置字段: " + k + "；凭据只接受引用"); });
        ObjectNode cfg = (ObjectNode) before.path("config").deepCopy();
        config.fields().forEachRemaining(entry -> cfg.set(entry.getKey(), entry.getValue()));
        if (!cfg.path("readOnly").isBoolean() || !cfg.path("readOnly").asBoolean()) throw new BizException("当前连接器只支持只读契约");
        if (!Set.of("HTTP_API", "JDBC_MYSQL", "FILE_JSON").contains(cfg.path("protocol").asText())) throw new BizException("协议不支持");
        if (!Set.of("SNAPSHOT", "WATERMARK").contains(cfg.path("syncMode").asText())) throw new BizException("同步方式不支持");
        if (!Set.of("EXPLICIT_TOMBSTONE", "FULL_RECONCILIATION").contains(cfg.path("deletionPolicy").asText())) throw new BizException("删除策略不支持");
        for (String key : allowed) {
            JsonNode v = cfg.path(key);
            if (Set.of("cadenceMinutes", "pageSize", "maxRetries", "overlapSeconds").contains(key)) {
                int min = key.equals("maxRetries") || key.equals("overlapSeconds") ? 0 : 1;
                int max = switch (key) { case "cadenceMinutes" -> 10080; case "pageSize" -> 10000; case "maxRetries" -> 10; default -> 86400; };
                if (!v.isIntegralNumber() || v.asLong() < min || v.asLong() > max) throw new BizException("参数超出范围: " + key);
            } else if (!key.equals("readOnly") && (!v.isTextual() || v.asText().length() > 1000)) throw new BizException("配置字段必须为1000字以内的文本: " + key);
        }
        String ref = cfg.path("credentialRef").asText();
        if (!ref.isEmpty() && !ref.matches("(?:env:[A-Z][A-Z0-9_]{1,100}|vault:[a-zA-Z0-9_./-]{1,150})"))
            throw new BizException("凭据只接受 env:变量名 或 vault:路径，不接收密码或令牌");
        checkEndpoint(cfg.path("endpoint").asText(), cfg.path("protocol").asText());
        result.set("config", cfg);
        JsonNode edits = patch.path("mappings");
        if (!edits.isArray() || edits.size() != before.path("mappings").size()) throw new BizException("映射目标集合已变化，请刷新；目标属性在本体管理中维护");
        Map<String,JsonNode> byTarget = new HashMap<>();
        for (JsonNode m : edits) {
            String key = m.path("entity").asText() + "." + m.path("target").asText();
            if (byTarget.put(key,m) != null) throw new BizException("存在重复映射目标: " + key);
        }
        ArrayNode mappings = json.createArrayNode();
        for (JsonNode base : before.path("mappings")) {
            JsonNode edit = byTarget.get(base.path("entity").asText() + "." + base.path("target").asText());
            if (edit == null) throw new BizException("映射目标不存在");
            ObjectNode m = base.deepCopy();
            for (String key : List.of("collection", "field", "type", "unit", "transform")) {
                JsonNode value = edit.path(key);
                if (!value.isTextual() || value.asText().length() > 256 || value.asText().chars().anyMatch(Character::isISOControl))
                    throw new BizException("映射字段格式错误: " + key);
                m.set(key,value);
            }
            if (!edit.path("enabled").isBoolean()) throw new BizException("映射启用状态格式错误");
            m.set("enabled", edit.path("enabled"));
            if (!m.path("enabled").asBoolean() && (m.path("key").asBoolean() || m.path("required").asBoolean())) throw new BizException("身份与必填字段不能移出校验范围");
            if (!m.path("transform").asText().equals("IDENTITY")) throw new BizException("暂只支持直接映射；单位换算需独立审核");
            mappings.add(m);
        }
        result.set("mappings", mappings);
        if (encode(result).length() > 512000) throw new BizException("配置过大");
        return result;
    }

    static void checkEndpoint(String endpoint, String protocol) {
        if (endpoint.isBlank()) return;
        try {
            String input = protocol.equals("JDBC_MYSQL") ? endpoint.replaceFirst("^jdbc:", "") : endpoint;
            if (protocol.equals("FILE_JSON")) {
                if (!endpoint.matches("[a-zA-Z0-9_./-]{1,256}") || endpoint.contains("..")) throw new IllegalArgumentException();
                return;
            }
            URI uri = URI.create(input);
            Set<String> schemes = protocol.equals("JDBC_MYSQL") ? Set.of("mysql") : Set.of("http", "https");
            if (!schemes.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new BizException("端点格式错误；请使用不含账号、密码、查询参数的地址"); }
    }

    ObjectNode check(JsonNode doc, Set<String> currentTargets) {
        ArrayNode issues = json.createArrayNode();
        JsonNode cfg = doc.path("config");
        for (String field : List.of("endpoint", "namespace", "owner", "permissionScope", "identityPolicy", "timePolicy", "reconciliation"))
            if (cfg.path(field).asText().isBlank()) issue(issues,"error",field,"缺少接入配置");
        if (!cfg.path("protocol").asText().equals("FILE_JSON") && cfg.path("credentialRef").asText().isBlank())
            issue(issues, doc.path("demo").asBoolean() ? "warning" : "error", "credentialRef", "未配置凭据引用；不会发起连接或取数");
        if (cfg.path("syncMode").asText().equals("WATERMARK") && cfg.path("watermarkField").asText().isBlank())
            issue(issues,"error","watermarkField","增量同步需要源更新时间或单调游标");
        JsonNode baseline = template(doc.path("templateCode").asText());
        Map<String,JsonNode> expected = new HashMap<>();
        for (JsonNode m : baseline.path("mappings")) expected.put(m.path("entity").asText()+"."+m.path("target").asText(),m);
        int configured = 0, disabled = 0;
        for (JsonNode m : doc.path("mappings")) {
            String target = m.path("entity").asText()+"."+m.path("target").asText();
            JsonNode base = expected.get(target);
            if (!currentTargets.contains(target)) issue(issues,"error",target,"目标概念或属性已不存在");
            if (!m.path("enabled").asBoolean()) { disabled++; if (m.path("required").asBoolean() || m.path("key").asBoolean()) issue(issues,"error",target,"必填或身份字段不可停用"); continue; }
            if (m.path("collection").asText().isBlank() || m.path("field").asText().isBlank()) issue(issues,"error",target,"源集合或字段待确认");
            else configured++;
            if (base == null) { issue(issues,"error",target,"目标不在初始化契约中"); continue; }
            if (!m.path("type").asText().equals(base.path("type").asText())) issue(issues,"error",target,"源类型与目标契约不同，直接映射不可通过");
            if (!m.path("unit").asText().equals(base.path("unit").asText())) issue(issues,"error",target,"单位不一致，需先明确换算规则");
            if (doc.path("demo").asBoolean() && (!m.path("collection").equals(base.path("collection")) || !m.path("field").equals(base.path("field"))))
                issue(issues,"error",target,"演示路径与现有适配定义不一致");
        }
        long errors = 0; for (JsonNode i : issues) if (i.path("severity").asText().equals("error")) errors++;
        ObjectNode result = json.createObjectNode();
        result.put("valid", errors == 0); result.put("errors", errors); result.put("configuredFields", configured); result.put("disabledFields", disabled);
        result.put("checkedAt", java.time.OffsetDateTime.now().toString()); result.put("version",doc.path("version").asInt());
        result.put("scope", "仅配置完整性、现有目标字段与基线类型/单位检查；未连接源系统，未校验数据质量，未发布运行时映射");
        result.set("issues",issues); return result;
    }

    private void issue(ArrayNode issues, String severity, String target, String message) {
        issues.addObject().put("severity",severity).put("target",target).put("message",message);
    }
    private JsonNode template(String code) {
        for (JsonNode t : templates) if (t.path("code").asText().equals(code)) return t;
        throw new BizException("未知连接器模板: " + code);
    }
    private ObjectNode clean(ObjectNode doc) {
        ObjectNode result = doc.deepCopy();
        result.remove(List.of("version","validation","contractValidated","updatedBy","updatedAt","runtimeEnabled","liveConnectionTested"));
        return result;
    }
    private void audit(String code, String operation, int version, JsonNode detail) {
        jdbc.update("INSERT INTO fp_connector_log(connector_code,operation,version,actor,detail) VALUES (?,?,?,?,?)",
                code, operation, version, Objects.requireNonNullElse(CurrentUser.username(), "baseline-initializer"), encode(detail));
    }
    private void writer() { if (!CurrentUser.hasAnyRole("ADMIN","EDITOR")) throw new BizException("连接器维护需要管理员或建模员权限"); }
    private String encode(JsonNode node) { try { return json.writeValueAsString(node); } catch (IOException e) { throw new IllegalStateException(e); } }
    private JsonNode parse(String source) { try { return json.readTree(source); } catch (IOException e) { throw new IllegalStateException("连接器配置无法读取", e); } }
}
