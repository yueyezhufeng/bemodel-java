package com.bemodel.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bemodel.knowledge.entity.Knowledge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeMapper extends BaseMapper<Knowledge> {
}
