package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.seatunnel.web.dao.entity.User;

/**
 * user mapper interface
 */
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT id FROM t_seatunnel_web_user WHERE id = #{id} FOR UPDATE")
    Integer selectIdForUpdate(@Param("id") Integer id);

}
