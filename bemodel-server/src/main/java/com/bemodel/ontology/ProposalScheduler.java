package com.bemodel.ontology;

import com.bemodel.ontology.service.OntologyProposalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时提案生成（P1a）：cron 读 bemodel.proposal.cron（默认每日 03:00 低峰），
 * 每轮 ≤ bemodel.proposal.top-n 次 LLM 调用。失败只打日志，下个周期重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposalScheduler {

    private final OntologyProposalService proposalService;

    @Scheduled(cron = "${bemodel.proposal.cron:-}")
    public void generate() {
        try {
            log.info("定时提案生成完成: {}", proposalService.run());
        } catch (Exception e) {
            log.warn("定时提案生成失败（下个周期重试）: {}", e.getMessage());
        }
    }
}
