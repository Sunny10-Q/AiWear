package com.sunny.aiwear_b.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sunny.aiwear_b.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件记录数据访问层。
 */
@Mapper
public interface FileMapper extends BaseMapper<FileRecord> {
}
