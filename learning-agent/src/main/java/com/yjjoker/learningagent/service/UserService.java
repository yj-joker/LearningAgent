package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.page.PageResult;
import com.yjjoker.learningagent.page.UserPageRequest;
import com.yjjoker.learningagent.vo.UserPageItemVO;
import com.yjjoker.learningagent.vo.UserVO;

public interface UserService {
    UserVO register(UserDTO userDTO);
    UserVO login(UserDTO userDTO);

    PageResult<UserPageItemVO> query(UserPageRequest request);
}
