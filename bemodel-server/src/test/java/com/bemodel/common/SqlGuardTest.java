package com.bemodel.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * clampLimit 只认语句末级 LIMIT:CTE/子查询内层的 LIMIT 不约束外层行数,
 * 末尾超大 LIMIT 直接改写——这是「下钻不把业务库整表拉回平台」承诺的钉子。
 */
class SqlGuardTest {

    @Test
    void appendLimitWhenMissing() {
        assertEquals("SELECT 1 LIMIT 200", SqlGuard.clampLimit("SELECT 1", 200));
    }

    @Test
    void keepTailLimitWithinCap() {
        String sql = "SELECT * FROM t LIMIT 50";
        assertEquals(sql, SqlGuard.clampLimit(sql, 200));
    }

    @Test
    void rewriteOversizedTailLimit() {
        assertEquals("SELECT * FROM t LIMIT 200",
                SqlGuard.clampLimit("SELECT * FROM t LIMIT 999999999", 200));
    }

    @Test
    void innerLimitDoesNotCount() {
        String sql = "WITH top AS (SELECT id FROM t ORDER BY x LIMIT 10) "
                + "SELECT top.*, d.* FROM top JOIN detail d ON d.pid = top.id";
        assertEquals(sql + " LIMIT 200", SqlGuard.clampLimit(sql, 200));
    }

    @Test
    void commaFormCountsSecondNumber() {
        assertEquals("SELECT * FROM t LIMIT 100, 200",
                SqlGuard.clampLimit("SELECT * FROM t LIMIT 100, 999999", 200));
        String ok = "SELECT * FROM t LIMIT 100, 50";
        assertEquals(ok, SqlGuard.clampLimit(ok, 200));
    }
}
