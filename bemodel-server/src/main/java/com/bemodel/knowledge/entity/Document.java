package com.bemodel.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 制度文档（知识轨非结构化载体）：解析文本落库，二进制原件不存 */
@Data
@TableName("bm_document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String docType;
    private String source;
    private String status;
    private Integer version;
    private String owner;
    private String uploadedBy;
    private Integer fileSize;
    private String contentHash;
    private String contentText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
