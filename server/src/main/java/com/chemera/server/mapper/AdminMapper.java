package com.chemera.server.mapper;

import com.chemera.server.entity.AdminUser;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AdminMapper {
    @Select("SELECT * FROM admin_user WHERE username=#{u}")
    AdminUser findByUsername(String u);

    @Select("SELECT * FROM admin_user WHERE id=#{id}")
    AdminUser findById(long id);

    /** 列表不返回 pass_hash：后台页面只需要用户名、角色与状态。 */
    @Select("SELECT id,username,role,status,must_change_password,created_at,last_login_at,updated_at"
            + " FROM admin_user ORDER BY id")
    List<AdminUser> list();

    @Insert("INSERT INTO admin_user(username,pass_hash,role,must_change_password)"
            + " VALUES(#{username},#{passHash},#{role},#{mustChangePassword})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminUser a);

    @Update("UPDATE admin_user SET last_login_at=NOW() WHERE id=#{id}")
    int touchLogin(long id);

    @Select("SELECT COUNT(*) FROM admin_user")
    long count();

    /** 换口令与清"必须改密"位必须一次做完，否则中途崩溃就把账号留在锁定状态。 */
    @Update("UPDATE admin_user SET pass_hash=#{passHash},must_change_password=#{must} WHERE id=#{id}")
    int updatePass(@Param("id") long id, @Param("passHash") String passHash, @Param("must") int must);

    @Update("UPDATE admin_user SET role=#{role} WHERE id=#{id}")
    int updateRole(@Param("id") long id, @Param("role") String role);

    @Update("UPDATE admin_user SET status=#{status} WHERE id=#{id}")
    int updateStatus(@Param("id") long id, @Param("status") int status);

    @Delete("DELETE FROM admin_user WHERE id=#{id}")
    int delete(long id);

    /** 还在岗的超管数：停用/降级/删除自己的角色前必须确认不会归零，否则后台永久锁死。 */
    @Select("SELECT COUNT(*) FROM admin_user WHERE role='super' AND status=0")
    long countActiveSuper();
}
