package com.bemodel.modeling;

import com.bemodel.common.BizException;
import com.bemodel.instance.InstanceService;
import com.bemodel.llm.LlmLogService;
import com.bemodel.modeling.entity.Action;
import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.service.ActionService;
import com.bemodel.modeling.service.ReleaseService;
import com.bemodel.modeling.service.RuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本体六要素（名词/动词/属性/规则/动作/实例）与统一版本、LLM审计验证。
 */
@SpringBootTest
class ModelingElementsTest {

    @Autowired
    private RuleService ruleService;
    @Autowired
    private ActionService actionService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private InstanceService instanceService;
    @Autowired
    private LlmLogService llmLogService;
    @Autowired
    private com.bemodel.llm.mapper.LlmLogMapper llmLogMapper;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** 发布审批门禁（P0②）用：以指定角色构造认证主体 */
    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void rulesAndActionsSeeded() {
        List<Rule> rules = ruleService.list("");
        assertEquals(14, rules.size(), "应预置14条业务规则（6条治理+8条病案质控，V13新增过敏禁忌/剂量上限）");
        assertTrue(rules.stream().anyMatch(r -> "CANCEL_NOT_REFUND".equals(r.getMetricCode())),
                "取消医嘱不得计费规则应绑定可执行指标");

        List<Action> actions = actionService.list("");
        assertEquals(9, actions.size(), "应预置9个业务动作");
        assertTrue(actions.stream().anyMatch(a ->
                "取消医嘱".equals(a.getName()) && a.getToStatus().equals("已取消")));
    }

    @Test
    void ruleStateMachineWorks() {
        Rule rule = new Rule();
        rule.setRuleCode("RULE-TEST-001");
        rule.setName("测试规则");
        rule.setConceptCode("FEE_DETAIL");
        rule.setRuleType("约束");
        rule.setExpression("测试表达式");
        ruleService.create(rule);
        assertEquals("DRAFT", rule.getStatus());

        assertThrows(BizException.class, () -> ruleService.transition("RULE-TEST-001", "PUBLISHED"),
                "草稿不能直接发布");
        ruleService.transition("RULE-TEST-001", "REVIEW");
        Rule published = ruleService.transition("RULE-TEST-001", "PUBLISHED");
        assertEquals(2, published.getVersion());
        ruleService.removeById(rule.getId());
    }

    @Test
    void releasePublishShouldSnapshotPublishedElements() {
        loginAs("ADMIN"); // 发布审批门禁（V29/P0②）：需评审员或管理员
        String before = releaseService.currentTag();
        Release release = releaseService.publish("测试发布", "自动化测试", true); // force：跳过覆盖类 WARN（门禁单测另证）
        assertNotNull(release.getVersionTag());
        if (before == null) {
            assertEquals("v1.0", release.getVersionTag());
        }
        assertTrue(release.getElementCount() > 50, "快照应包含全部已发布元素: " + release.getElementCount());
        assertTrue(release.getSnapshotJson().contains("\"concepts\""));
        assertTrue(release.getSnapshotJson().contains("\"rules\""));
        // 清理测试产生的版本，避免污染演示环境
        releaseService.removeById(release.getId());
    }

    @Test
    void instanceBrowserShouldProjectPhysicalRowsToConceptAttrs() {
        Map<String, Object> result = instanceService.instances("FEE_DETAIL", null, 1, 10);
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
        assertFalse(sources.isEmpty());
        Map<String, Object> first = sources.get(0);
        assertEquals("DS_HIS", first.get("dsCode"));
        assertEquals("fee_detail", first.get("tableName"));
        List<Map<String, Object>> instances = (List<Map<String, Object>>) first.get("instances");
        assertFalse(instances.isEmpty());
        // 值字典翻译：费用状态应为标准口径而非物理码
        assertTrue(instances.stream().anyMatch(i -> "正常".equals(i.get("费用状态"))),
                "实例的费用状态应被翻译为标准口径: " + instances.get(0));
    }

    @Test
    void llmCallsShouldBeAuditedWithOntologyVersion() {
        // Plan 9 起：无 Key 调用不再落审计行（DeepSeekClient 无 key 直接降级），
        // 「审计行绑定本体版本」契约改由直写真实 LlmLogService 钉住——与 key/Ollama 存活、
        // 共享库其他测试的落行时序彻底解耦（旧写法取「全局最新行」已被 EMBED_QUERY 行撞翻一次）
        List<com.bemodel.llm.LlmLog> before = llmLogService.recent();
        long beforeMaxId = before.isEmpty() ? 0 : before.get(0).getId();
        llmLogService.log("SEARCH_ANSWER", "deepseek-v4-flash", "primary", "审计绑定本体版本-测试", 5L, true, null);
        com.bemodel.llm.LlmLog latest = llmLogService.recent().get(0);
        assertTrue(latest.getId() > beforeMaxId, "断言应落在新写的审计行上，而非库内既有行");
        assertEquals("SEARCH_ANSWER", latest.getCallType());
        assertNotNull(latest.getOntologyVersion(), "日志必须绑定本体版本");
        assertNotNull(latest.getLatencyMs());
        assertEquals(1, latest.getSuccess());
        llmLogMapper.deleteById(latest.getId()); // 测试行即写即清，演示库不留垃圾
    }
}
