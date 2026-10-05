package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账运行记录:每次跑对账组落一条(各口径实测值 + 差额 + 下钻明细)。
 * 对应 V43 bm_reconcile_run。
 */
@Data
@TableName("bm_reconcile_run")
public class ReconcileRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String groupCode;
    /** 各口径实测值 JSON,如 {"PILOT_DISCHARGE_MED":153,"PILOT_DISCHARGE_SETTLE":173} */
    private String valuesJson;
    /** 口径间差额(最大-最小;有口径实测失败则为 null) */
    private Long diffValue;
    /** 下钻明细行 JSON(最多 200 行,null=未配下钻) */
    private String drillJson;
    /** 下钻明细行数(-1=下钻执行失败,null=未配下钻) */
    private Integer drillCount;
    /** 归因桶结果 JSON:{buckets:[{label,sign,count,error}],sum,matchesDiff}(null=未配桶) */
    private String bucketsJson;
    /** 失败口径信息(空=全部口径实测成功) */
    private String errorMsg;
    private LocalDateTime ranAt;
}
