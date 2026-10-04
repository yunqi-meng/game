package com.chemera.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface ModerationMapper {
    @Select("SELECT id,reporter,target_user targetUser,kind,ref_id refId,reason,status,created_at createdAt,handled_by handledBy " +
            "FROM report WHERE (#{st}='' OR status=#{st}) ORDER BY id DESC LIMIT #{size} OFFSET #{off}")
    List<Map<String, Object>> pageReports(@Param("st") String st, @Param("size") int size, @Param("off") int off);

    @Insert("INSERT INTO report(reporter,target_user,kind,ref_id,reason) VALUES(#{reporter},#{target},#{kind},#{ref},#{reason})")
    int addReport(@Param("reporter") Long reporter, @Param("target") Long target,
                  @Param("kind") String kind, @Param("ref") String ref, @Param("reason") String reason);

    @Update("UPDATE report SET status=#{st}, handled_by=#{by} WHERE id=#{id}")
    int handleReport(@Param("id") long id, @Param("st") String st, @Param("by") String by);

    @Select("SELECT id,word,level FROM sensitive_word ORDER BY id")
    List<Map<String, Object>> words();

    @Insert("INSERT INTO sensitive_word(word,level) VALUES(#{word},#{level}) ON DUPLICATE KEY UPDATE level=VALUES(level)")
    int upsertWord(@Param("word") String word, @Param("level") int level);

    @Delete("DELETE FROM sensitive_word WHERE id=#{id}")
    int deleteWord(long id);

    @Select("SELECT word FROM sensitive_word")
    List<String> allWords();
}
