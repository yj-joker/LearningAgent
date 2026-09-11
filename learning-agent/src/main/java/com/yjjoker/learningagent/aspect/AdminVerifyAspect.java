package com.yjjoker.learningagent.aspect;

import com.yjjoker.learningagent.annotation.AdminAnnotation;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Slf4j
@Component
public class AdminVerifyAspect {
    @Before("@annotation(adminAnnotation)")
    public void checkAdmin(AdminAnnotation adminAnnotation) {
      UserRoleEnum userRole = BaseContext.getCurrentRole();
      if (userRole != UserRoleEnum.ADMIN) {
            log.warn("[权限] 非管理员用户尝试访问管理员接口 userId={}", BaseContext.getCurrentId());
            throw new ViolationOperationException("需要管理员权限");
        }
      log.info("[权限] 管理员用户访问管理员接口 userId={}", BaseContext.getCurrentId());
    }
}
