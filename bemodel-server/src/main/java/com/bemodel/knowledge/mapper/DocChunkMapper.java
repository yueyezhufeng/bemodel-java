package com.bemodel.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bemodel.knowledge.entity.DocChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocChunkMapper extends BaseMapper<DocChunk> {

    /** 关键词路：FULLTEXT 布尔检索（ngram 分词），按相关度降序，仅 PUBLISHED 文档片段。
     *  q 须为 {@link FulltextQuery#build} 产物（bigram 可选词项）；直接传原始问句会被 ngram
     *  布尔模式当短语搜索，自然短语必零命中。 */
    @Select("SELECT c.* FROM bm_doc_chunk c JOIN bm_document d ON c.document_id = d.id "
            + "WHERE d.status = 'PUBLISHED' AND MATCH(c.content) AGAINST(#{q} IN BOOLEAN MODE) "
            + "ORDER BY MATCH(c.content) AGAINST(#{q} IN BOOLEAN MODE) DESC LIMIT #{limit}")
    List<DocChunk> fulltextSearch(@Param("q") String q, @Param("limit") int limit);

    /** 向量路取数：PUBLISHED 且已向量化（embedding 非空）的片段，Java 端余弦暴力扫 */
    @Select("SELECT c.* FROM bm_doc_chunk c JOIN bm_document d ON c.document_id = d.id "
            + "WHERE d.status = 'PUBLISHED' AND c.embedding IS NOT NULL "
            + "ORDER BY c.id LIMIT #{limit}")
    List<DocChunk> selectPublishedWithEmbedding(@Param("limit") int limit);
}
