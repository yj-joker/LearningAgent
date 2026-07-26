package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserRepository {
    @Select("select id,username,password,role,avatar_url,created_at,updated_at " +
            "from user where id=#{id}")
    User findUserById(Long id);
    @Select("select id,username,password,role,avatar_url,created_at,updated_at " +
            "from user where username= #{username}")
    User findUserByUsername(String username);
    @Insert("insert into user (id,username,password,role,avatar_url,created_at,updated_at) " +
            "values(#{id},#{username},#{password},#{role},#{avatarUrl},#{createdAt},#{updatedAt})")
    int save(User user);
}
