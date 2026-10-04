package com.chemera.server.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemMapper {
    /** 健康检查用的最小查询：能跑通就说明连接池与库都在。 */
    @Select("SELECT 1")
    int ping();

    @Select("SELECT COUNT(*) FROM app_user")
    long userCount();
}
