package com.yjjoker.learningagent.interceptor;

import com.yjjoker.learningagent.exception.AnalysisErrorException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {
    private final JwtService jwtService;
    public LoginInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }
    //请求处理前
    @Override
    public boolean preHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        //从请求头当中获取JWT
        String jwt = request.getHeader("Authorization");
        if (jwt==null) {
            throw new NotFountException("用户未登录");
        }
        if(!jwt.startsWith("Bearer ")){
            throw new AnalysisErrorException("非法访问");
        }
        //解析JWT，获取到用户ID
        String token = jwt.substring(7);
        Long userId;
        UserRoleEnum userRole;
        try {
            userId = jwtService.parseUserId(token);
            userRole = jwtService.parseUserRole(token);
        } catch (Exception e) {
            throw new AnalysisErrorException("非法访问");
        }
        //添加用户id到当前线程当中
        BaseContext.setCurrentId(userId);
        //添加用户角色到当前线程当中
        BaseContext.setCurrentRole(userRole);
        log.info("用户已经登录，放行");
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, @Nullable Exception ex) throws Exception {
        BaseContext.removeCurrentId();
        BaseContext.removeCurrentRole();
    }
}
