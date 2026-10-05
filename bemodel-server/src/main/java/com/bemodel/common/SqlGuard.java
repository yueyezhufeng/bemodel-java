package com.bemodel.common;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理端配置 SQL(探针/下钻)的只读护栏:只放行 SELECT/WITH,拒绝一切写操作关键字。
 * 管理端 SQL 由可信人员配置,这里防的是误配置与越权写入,不做 SemanticQaService
 * 那种表/列级白名单(物理表不在平台台账内的合法 SQL 会被误伤)。
 */
public final class SqlGuard {

    private static final Set<String> FORBIDDEN = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "REPLACE", "MERGE", "CALL", "SET", "LOCK", "UNLOCK",
            "RENAME", "KILL", "SHUTDOWN");

    private static final Pattern FORBIDDEN_WORD = Pattern.compile(
            "\\b(" + String.join("|", FORBIDDEN) + ")\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern TAIL_COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private SqlGuard() {
    }

    /**
     * 校验并规整一条管理端 SQL:必须 SELECT/WITH 开头、不含写关键字、去掉结尾分号。
     * 不合规抛 BizException;返回可执行 SQL。
     */
    public static String requireReadOnly(String sql, String label) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(label + "为空");
        }
        String trimmed = TAIL_COMMENT.matcher(sql).replaceAll(" "); // 先去注释,防止写关键字藏在注释里误判,也防误判
        trimmed = trimmed.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            throw new BizException(label + "必须是只读查询(SELECT/WITH 开头)");
        }
        if (FORBIDDEN_WORD.matcher(trimmed).find()) {
            throw new BizException(label + "包含写操作关键字,已被拒绝");
        }
        if (Pattern.compile("(?i)\\bFOR\\s+(UPDATE|SHARE)\\b").matcher(trimmed).find()) {
            throw new BizException(label + "不允许 FOR UPDATE/SHARE");
        }
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /** 语句末级的 LIMIT [offset,] n / LIMIT n OFFSET m:只有末级 LIMIT 才约束整个结果集 */
    private static final Pattern TAIL_LIMIT =
            Pattern.compile("\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+\\d+)?\\s*$", Pattern.CASE_INSENSITIVE);

    /** MySQL 的 LIMIT a,b 里 b 才是行数 */
    private static final Pattern TAIL_LIMIT_COMMA =
            Pattern.compile("\\bLIMIT\\s+\\d+\\s*,\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * 无末级 LIMIT 的查询补上,防止下钻把业务库整表拉回平台。
     * CTE/子查询内层的 LIMIT 不约束外层行数,不能当「已限行」的证据;
     * 末尾超大 LIMIT 直接改写,而不是放行。
     */
    public static String clampLimit(String sql, int maxRows) {
        Matcher comma = TAIL_LIMIT_COMMA.matcher(sql);
        Matcher simple = TAIL_LIMIT.matcher(sql);
        Matcher m = comma.find() ? comma : (simple.find() ? simple : null);
        if (m == null) {
            return sql + " LIMIT " + maxRows;
        }
        long declared = Long.parseLong(m.group(1));
        if (declared <= maxRows) {
            return sql;
        }
        return sql.substring(0, m.start(1)) + maxRows + sql.substring(m.end(1));
    }
}
