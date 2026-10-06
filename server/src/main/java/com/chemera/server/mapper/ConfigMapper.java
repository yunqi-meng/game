package com.chemera.server.mapper;

import com.chemera.server.entity.AppConfigRow;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ConfigMapper {
    @Select("SELECT cfg_key, cfg_value FROM app_config")
    List<Map<String, Object>> allRaw();

    /**
     * 配置列表的搜索条件（H6-3）：总数与列表共用这一段，理由同
     * {@link ModerationMapper#REPORT_WHERE}——两处手写就会漂，漂了面板的页数开始说瞎话。
     *
     * <p>只认库里的三列（键名 / 分类 / 备注）。键的<b>中文名不在库里</b>，它在
     * {@code game/ConfigSpec}（Java 那份说明书）里，所以搜索框明说了"按键名、分类或备注"，
     * 不假装能用中文名称搜。
     */
    String LIST_WHERE = "<if test=\"q!=null and q!=''\">WHERE (cfg_key LIKE CONCAT('%',#{q},'%') " +
            "OR category LIKE CONCAT('%',#{q},'%') OR remark LIKE CONCAT('%',#{q},'%'))</if>";

    /** 面板列表（分页 + 可搜索）。取代原先那句 {@code SELECT *…} 的整表回传（H6-3）。 */
    @Select("<script>SELECT * FROM app_config " + LIST_WHERE + " ORDER BY category, cfg_key " +
            "LIMIT #{size} OFFSET #{off}</script>")
    List<AppConfigRow> page(@Param("q") String q, @Param("size") int size, @Param("off") int off);

    /** 同一条件下的总行数（H6-3）：面板的"新增同名会覆盖"预检与分页页数都读它。 */
    @Select("<script>SELECT COUNT(*) FROM app_config " + LIST_WHERE + "</script>")
    long count(@Param("q") String q);

    @Select("SELECT * FROM app_config WHERE cfg_key=#{k}")
    AppConfigRow get(String k);

    @Insert("INSERT INTO app_config(cfg_key,cfg_value,category,remark,updated_by) " +
            "VALUES(#{cfgKey},#{cfgValue},#{category},#{remark},#{updatedBy}) " +
            "ON DUPLICATE KEY UPDATE cfg_value=VALUES(cfg_value),category=VALUES(category),remark=VALUES(remark),updated_by=VALUES(updated_by)")
    int upsert(AppConfigRow r);

    /**
     * 只插入（H6-2）：面板处于"新增一个键"的状态时用，撞上已有键会抛 DuplicateKeyException，
     * 由控制器翻成 409——那说明这一行在你打开对话框之后被别人建出来了，
     * 走 {@link #upsert} 的话等于把人家那份盖掉。
     */
    @Insert("INSERT INTO app_config(cfg_key,cfg_value,category,remark,updated_by) " +
            "VALUES(#{r.cfgKey},#{r.cfgValue},#{r.category},#{r.remark},#{r.updatedBy})")
    int insertOnly(@Param("r") AppConfigRow r);

    /**
     * 条件更新（乐观锁的那半边）：只有 {@code updated_at} 还是面板拿到的那个值才写。
     *
     * <p>刻意不自己写 {@code updated_at}：{@code V1__baseline} 给这一列设了
     * {@code ON UPDATE CURRENT_TIMESTAMP(3)}，让库来盖章，才不会出现在线两笔写谁先谁后由 Java 侧时钟决定。
     * 返回 0 就是"这一行已经不是你看到的那一行了"（或已被删）。
     *
     * <p>Connector/J 默认按"匹配行数"回报（不是"改变行数"），所以把同一个值原样再存一次仍回 1，
     * 不会让连点两次保存的运营误判成冲突——e2e 里钉着这条。
     */
    @Update("UPDATE app_config SET cfg_value=#{r.cfgValue},category=#{r.category},remark=#{r.remark},updated_by=#{r.updatedBy} " +
            "WHERE cfg_key=#{r.cfgKey} AND updated_at=#{expect}")
    int updateIfUnchanged(@Param("r") AppConfigRow r, @Param("expect") LocalDateTime expect);

    /** 条件删除：同上，只有这一行还是面板看到的那一版才删（防止删掉别人刚改好的内容）。 */
    @Delete("DELETE FROM app_config WHERE cfg_key=#{k} AND updated_at=#{expect}")
    int deleteIfUnchanged(@Param("k") String k, @Param("expect") LocalDateTime expect);

    @Delete("DELETE FROM app_config WHERE cfg_key=#{k}")
    int delete(String k);
}
