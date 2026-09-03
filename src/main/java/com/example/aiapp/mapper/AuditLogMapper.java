package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志数据访问接口。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {

    /**
     * 按创建时间倒序分页查询审计日志。
     *
     * @param page 分页参数
     * @return 审计日志分页结果
     */
    IPage<AuditLogEntity> selectPageOrderByCreatedAtDesc(Page<AuditLogEntity> page);
}
