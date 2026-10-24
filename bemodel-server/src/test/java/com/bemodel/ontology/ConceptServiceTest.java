package com.bemodel.ontology;

import com.bemodel.common.BizException;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.service.ConceptService;
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

@SpringBootTest
class ConceptServiceTest {

    @Autowired
    private ConceptService conceptService;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** 评审门禁（P0②）用：以指定角色构造认证主体（服务层 CurrentUser 读 SecurityContext） */
    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void conceptDetailShouldAggregateAttributesAndRelations() {
        Map<String, Object> detail = conceptService.detail("Lot");
        Concept concept = (Concept) detail.get("concept");
        assertEquals("费用明细", concept.getName());
        assertFalse(((java.util.List<?>) detail.get("attributes")).isEmpty());
        assertFalse(((java.util.List<?>) detail.get("relations")).isEmpty());
        assertFalse(((java.util.List<?>) detail.get("terms")).isEmpty());
    }

    @Test
    void statusTransitionShouldFollowStateMachine() {
        Concept draft = new Concept();
        draft.setCode("TEST_CONCEPT");
        draft.setName("测试概念");
        draft.setDomainCode("OPS");
        conceptService.create(draft);

        Concept reviewed = conceptService.transition("TEST_CONCEPT", "REVIEW");
        assertEquals("REVIEW", reviewed.getStatus());

        loginAs("ADMIN"); // PUBLISHED 转换受评审门禁（V29/P0②），需评审员或管理员
        Concept published = conceptService.transition("TEST_CONCEPT", "PUBLISHED");
        assertEquals("PUBLISHED", published.getStatus());
        assertEquals(2, published.getVersion(), "发布后版本号应+1");

        assertThrows(BizException.class, () -> conceptService.transition("TEST_CONCEPT", "REVIEW"),
                "PUBLISHED不允许回退到REVIEW");

        // 清理
        conceptService.lambdaUpdate().eq(Concept::getCode, "TEST_CONCEPT").remove();
    }
}
