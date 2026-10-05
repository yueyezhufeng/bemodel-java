package com.bemodel.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bemodel.knowledge.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
