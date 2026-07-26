package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.vo.UserVO;

public interface UserService {
    UserVO register(UserDTO userDTO);
}
