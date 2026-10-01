package com.yjjoker.learningagent.config;

import com.yjjoker.learningagent.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 配置 HTTP 跨域和登录拦截；WebSocket 来源由专用配置单独限制。
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    // 注入现有登录拦截器，普通审批 HTTP 请求继续复用 JWT 鉴权。
    public WebMvcConfig(LoginInterceptor loginInterceptor) {
        this.loginInterceptor = loginInterceptor;
    }

    // 保留项目原有 HTTP 跨域规则，不把它当作 WebSocket 的身份认证。
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    // 仅放行登录、注册和专用握手入口；领票接口仍需要登录。
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 登录鉴权拦截器（最先执行）
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/learning-agent/user/login",
                        "/learning-agent/user/register",
                        // 握手不带 Authorization，由专用拦截器校验一次性票据。
                        "/agent/approvals/socket"
                )
                .order(1);

    }
}
