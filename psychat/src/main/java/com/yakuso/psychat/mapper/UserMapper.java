package com.yakuso.psychat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yakuso.psychat.entity.User;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE username = #{username}")
    User selectByUsername(String username);
}
