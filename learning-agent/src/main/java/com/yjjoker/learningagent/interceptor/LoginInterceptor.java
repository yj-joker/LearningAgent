package com.yjjoker.learningagent.interceptor;

import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.exception.AnalysisErrorException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.repository.UserRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    public LoginInterceptor(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }
    //请求处理前
    @Override
    public boolean preHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        //从请求头当中获取JWT
        String jwt = request.getHeader("Authorization");
        if (jwt==null) {
            throw new NotFountException("用户未登录");
        }
        //解析JWT，获取到用户ID
        String token = jwt.substring(7);
        Long userId;
        try {
            userId = jwtService.parseUserId(token);
        } catch (Exception e) {
            throw new AnalysisErrorException("非法访问");
        }
        //查询用户信息
        User user = userRepository.findUserById(userId);
        //用户不存在
        if (user==null) {
            throw new NotFountException("用户不存在");
        }
        //添加用户id到当前线程当中
        BaseContext.setCurrentId(userId);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, @Nullable Exception ex) throws Exception {
        BaseContext.removeCurrentId();
    }
}
