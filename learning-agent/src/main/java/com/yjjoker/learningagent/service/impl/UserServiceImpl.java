package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.PasswordErrorException;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.UserRepository;
import com.yjjoker.learningagent.service.UserService;
import com.yjjoker.learningagent.utils.JwtService;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@AllArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final SnowflakeIdGenerator id;
    //创建用户
    @Override
    public UserVO register(UserDTO userDTO) {
        //判断用户名是否重复
        User userByUsername = userRepository.findUserByUsername(userDTO.getUsername());
        if (userByUsername!=null) {
            log.error("用户已存在");
            throw new CreateErrorException("用户已存在");
        }
        User user = new User();
        //生成id
        user.setId(id.nextId());
        String newPassword = BCrypt.hashpw(userDTO.getPassword(), BCrypt.gensalt());
        user.setPassword(newPassword);
        user.setUsername(userDTO.getUsername());
        user.setAvatarUrl(userDTO.getAvatarUrl());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        user.setRole(UserRoleEnum.USER);
        int result;
        try {
             result = userRepository.save(user);
        } catch (Exception e) {
            if(e instanceof DuplicateKeyException){
                log.error("用户已存在");
                throw new CreateErrorException("用户已存在");
            }
            log.error("保存用户失败");
            throw new LearningAgentServiceException("保存用户失败，请稍后再试");
        }
        if (result!=1) {
            log.error("保存用户失败");
         throw new LearningAgentServiceException("保存用户失败，请稍后再试");
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user,userVO);
        log.info("用户保存成功");
        return userVO;
    }

    //登录
    @Override
    public UserVO login(UserDTO userDTO) {
        if(userDTO==null){
            log.error("用户信息不能为空");
            throw new CreateErrorException("用户信息不能为空");
        }
        User user = userRepository.findUserByUsername(userDTO.getUsername());
        if (user==null) {
            log.error("用户不存在");
            throw new NotFountException("用户名或密码错误");
        }
        //验证密码
        try {
            BCrypt.checkpw(userDTO.getPassword(), user.getPassword());
        } catch (Exception e) {
            throw new PasswordErrorException("用户名或密码错误");
        }

        //生成JWT
        String jwt = jwtService.createToken(user.getId(), user.getRole().name());

        log.info("用户JWT生成成功");
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user,userVO);
        userVO.setToken(jwt);
        log.info("用户登录成功");
        return userVO;
    }
}
