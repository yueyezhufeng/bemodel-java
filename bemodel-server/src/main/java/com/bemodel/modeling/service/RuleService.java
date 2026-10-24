package com.bemodel.modeling.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.common.StateMachine;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.mapper.RuleMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleService extends ServiceImpl<RuleMapper, Rule> {

    public List<Rule> list(String conceptCode) {
        return lambdaQuery()
                .eq(conceptCode != null && !conceptCode.isBlank(), Rule::getConceptCode, conceptCode)
                .orderByAsc(Rule::getRuleCode)
                .list();
    }

    public static void requireContract(Rule rule) {
        if (rule.getEngine() == null || rule.getEngine().isBlank()) rule.setEngine("CONTRACT");
        if (!"CONTRACT".equals(rule.getEngine())) throw new BizException("工业规则仅支持 CONTRACT 定义，不提供业务执行器");
    }

    public Rule create(Rule rule) {
        requireContract(rule);
        if (lambdaQuery().eq(Rule::getRuleCode, rule.getRuleCode()).count() > 0) {
            throw new BizException("规则编码已存在: " + rule.getRuleCode());
        }
        rule.setStatus("DRAFT");
        rule.setVersion(1);
        save(rule);
        return rule;
    }

    public Rule transition(String ruleCode, String target) {
        Rule rule = lambdaQuery().eq(Rule::getRuleCode, ruleCode).one();
        if (rule == null) {
            throw new BizException("规则不存在: " + ruleCode);
        }
        StateMachine.check(rule.getStatus(), target);
        rule.setStatus(target);
        if ("PUBLISHED".equals(target)) {
            rule.setVersion(rule.getVersion() + 1);
        }
        updateById(rule);
        return rule;
    }
}
