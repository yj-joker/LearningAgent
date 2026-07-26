package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.projectenum.UserTypeEnum;
import com.yjjoker.learningagent.repository.UserRepository;
import com.yjjoker.learningagent.service.UserService;
import com.yjjoker.learningagent.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
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
        UUID uuid = UUID.randomUUID();
        user.setId(uuid.getMostSignificantBits());
        String newPassword = BCrypt.hashpw(userDTO.getPassword(), BCrypt.gensalt());
        user.setPassword(newPassword);
        user.setUsername(userDTO.getUsername());
        user.setAvatarUrl(userDTO.getAvatarUrl());
        user.setUserType(UserTypeEnum.USER);
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
}
