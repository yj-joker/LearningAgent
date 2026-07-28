package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.page.UserPageRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserRepository {
    //根据id查询用户
    @Select("select id,username,password,role,avatar_url,created_at,updated_at " +
            "from user where id=#{id}")
    User findUserById(Long id);
    //根据用户名查询用户
    @Select("select id,username,password,role,avatar_url,created_at,updated_at " +
            "from user where username= #{username}")
    User findUserByUsername(String username);
    //保存用户
    @Insert("insert into user (id,username,password,role,avatar_url,created_at,updated_at) " +
            "values(#{id},#{username},#{password},#{role},#{avatarUrl},#{createdAt},#{updatedAt})")
    int save(User user);
    //根据可选条件统计用户数量
    long countByCondition(@Param("condition") UserPageRequest condition);

    //根据可选条件分页查询用户
    List<User> findPageByCondition(
            @Param("condition") UserPageRequest condition,
            @Param("offset") long offset,
            @Param("limit") int limit
    );
}
