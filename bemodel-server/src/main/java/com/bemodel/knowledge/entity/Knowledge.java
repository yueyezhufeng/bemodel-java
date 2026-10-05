package com.bemodel.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识条目（结构化专家经验）：客服话术与核对归因按 code 读取，编辑走审批 */
@Data
@TableName("bm_knowledge")
public class Knowledge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String title;
    private String content;
    private String appliesTo;
    private String conceptCode;
    private String source;
    private String status;
    private Integer version;
    private String owner;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
