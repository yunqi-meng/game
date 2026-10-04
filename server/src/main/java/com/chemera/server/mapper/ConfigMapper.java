package com.chemera.server.mapper;

import com.chemera.server.entity.AppConfigRow;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface ConfigMapper {
    @Select("SELECT cfg_key, cfg_value FROM app_config")
    List<Map<String, Object>> allRaw();

    @Select("SELECT * FROM app_config ORDER BY category, cfg_key")
    List<AppConfigRow> list();

    @Select("SELECT * FROM app_config WHERE cfg_key=#{k}")
    AppConfigRow get(String k);

    @Insert("INSERT INTO app_config(cfg_key,cfg_value,category,remark,updated_by) " +
            "VALUES(#{cfgKey},#{cfgValue},#{category},#{remark},#{updatedBy}) " +
            "ON DUPLICATE KEY UPDATE cfg_value=VALUES(cfg_value),category=VALUES(category),remark=VALUES(remark),updated_by=VALUES(updated_by)")
    int upsert(AppConfigRow r);

    @Delete("DELETE FROM app_config WHERE cfg_key=#{k}")
    int delete(String k);
}
