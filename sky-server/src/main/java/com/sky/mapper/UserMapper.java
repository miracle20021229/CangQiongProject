package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.web.bind.annotation.RequestMapping;

@Mapper
public interface UserMapper {
    /**
     * 根据openid查询用户
     * @param openId
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    public User getUserByOpenId(String openId);

    /**
     * 插入新用户
     * @param user
     */
    void insert(User user);

    /**
     * 根据id查用户
     * @param userId
     * @return
     */
    @Select("select 8 from user where id = #{id}")
    User getById(Long userId);
}
