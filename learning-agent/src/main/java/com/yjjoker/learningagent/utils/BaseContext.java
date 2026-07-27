package com.yjjoker.learningagent.utils;

import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import org.springframework.stereotype.Component;

@Component
public class BaseContext {

    private static final ThreadLocal<Long> threadLocalId = new ThreadLocal<>();
    private static final ThreadLocal<UserRoleEnum> threadLocalRole = new ThreadLocal<>();
    public static void setCurrentId(Long id){threadLocalId.set(id);}
    public static Long getCurrentId(){
        return threadLocalId.get();
    }
    public static void removeCurrentId(){
        threadLocalId.remove();
    }

    public static void setCurrentRole(UserRoleEnum role){
        threadLocalRole.set(role);
    }
    public static UserRoleEnum getCurrentRole(){
        return threadLocalRole.get();
    }
    public static void removeCurrentRole(){
        threadLocalRole.remove();
    }
}
