package com.chemera.server.mapper;

import com.chemera.server.entity.AuditLog;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditMapper {
    @Insert("INSERT INTO audit_log(admin,action,target,detail,ip) VALUES(#{admin},#{action},#{target},#{detail},#{ip})")
    int insert(AuditLog log);

    @Select("SELECT * FROM audit_log ORDER BY id DESC LIMIT #{size} OFFSET #{off}")
    List<Map<String, Object>> page(@Param("size") int size, @Param("off") int off);
}
