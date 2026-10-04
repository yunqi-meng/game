package com.chemera.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {
    @Insert("INSERT INTO analytics_event(user_id,event,props,day) VALUES(#{userId},#{event},#{props},CURRENT_DATE())")
    int insert(@Param("userId") Long userId, @Param("event") String event, @Param("props") String props);

    /** 游客转正：埋点事件换主。 */
    @Update("UPDATE analytics_event SET user_id=#{to} WHERE user_id=#{from}")
    int reassignUser(@Param("from") long from, @Param("to") long to);

    @Select("SELECT DATE(last_login_at) d, COUNT(DISTINCT id) n FROM app_user " +
            "WHERE last_login_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) GROUP BY d ORDER BY d")
    List<Map<String, Object>> loginTrend(@Param("days") int days);

    @Select("SELECT event, COUNT(*) n FROM analytics_event WHERE day >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "GROUP BY event ORDER BY n DESC")
    List<Map<String, Object>> eventCounts(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM app_user WHERE created_at >= CURDATE()")
    long newUsersToday();

    @Select("SELECT COUNT(*) FROM app_user WHERE DATE(last_login_at)=CURDATE()")
    long dauToday();

    @Select("SELECT COUNT(*) FROM app_user")
    long totalUsers();

    @Insert("INSERT INTO daily_stats(day,dau,new_users,reactions,booms,trades) VALUES(#{day},#{dau},#{nu},#{rx},#{bm},#{tr}) " +
            "ON DUPLICATE KEY UPDATE dau=VALUES(dau),new_users=VALUES(new_users),reactions=VALUES(reactions),booms=VALUES(booms),trades=VALUES(trades)")
    int upsertDaily(@Param("day") java.time.LocalDate day, @Param("dau") int dau, @Param("nu") int nu,
                    @Param("rx") int rx, @Param("bm") int bm, @Param("tr") int tr);

    @Select("SELECT JSON_EXTRACT(payload,'$.stats.success') v FROM user_save WHERE user_id=#{id}")
    Integer saveStatSuccess(long id);

    @Select("SELECT day,dau,new_users,reactions,booms,trades FROM daily_stats ORDER BY day DESC LIMIT #{limit}")
    List<Map<String, Object>> recentDaily(int limit);

    @Delete("DELETE FROM analytics_event WHERE day < DATE_SUB(CURDATE(), INTERVAL 180 DAY)")
    int purgeOld();

    /** 注销账号：清除该用户埋点。 */
    @Delete("DELETE FROM analytics_event WHERE user_id=#{id}")
    int purgeUser(long id);
}
