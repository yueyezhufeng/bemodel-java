package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账口径组:同一业务指标的多口径绑定(如 月出院人数 = 病案口径 + 结算口径)。
 * 对应 V43 bm_reconcile_group。
 */
@Data
@TableName("bm_reconcile_group")
public class ReconcileGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String groupCode;
    private String name;
    private String definition;
    /** 参与口径的指标编码,逗号分隔,至少 2 个 */
    private String metricCodes;
    /** 明细下钻执行的数据源编码(空=不下钻) */
    private String drillDsCode;
    /** 差额明细下钻 SQL(可选项,只读) */
    private String drillSql;
    private String owner;
    /** 分歧状态:OPEN待认领/CLAIMED对账中/RESOLVED已裁决(对应 V44) */
    private String disputeStatus;
    private String disputeOwner;
    /** 裁决结论:以哪侧口径为准(一句话,入档给对账会/复盘看;不会自动改任何探针或报表口径) */
    private String verdict;
    private String verdictNote;
    private LocalDateTime verdictAt;
    /** 归因桶配置 JSON:[{label,dsCode,sql,sign}](空=不拆堆;桶 SQL 期望返回单数值) */
    private String bucketsJson;
    /** 是否挂每日巡检(0/1,对应 InspectService.runReconcileScheduled) */
    private Integer scheduled;
    /** 认领人登录名(V45 起取登录身份;旧行 NULL=自报时代,展示回退 dispute_owner) */
    private String claimedBy;
    /** 裁决人登录名(V45 起 resolve 取登录身份落库) */
    private String resolvedBy;
    private LocalDateTime createdAt;
}
