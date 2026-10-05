package com.bemodel.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 文档片段（检索与引用的最小单元）：embedding 空 = 未向量化，向量路对该片段关闭 */
@Data
@TableName("bm_doc_chunk")
public class DocChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Integer seq;
    private String heading;
    private String content;
    private String embedding;
    private String conceptCode;
}
