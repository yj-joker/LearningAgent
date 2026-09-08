package com.yjjoker.learningagent.utils;

import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import org.springframework.stereotype.Component;

@Component
public class BaseContext {

    private static final ThreadLocal<Long> threadLocalId = new ThreadLocal<>();
    private static final ThreadLocal<UserRoleEnum> threadLocalRole = new ThreadLocal<>();
    //获取，设置，删除当前线程用户id
    public static void setCurrentId(Long id){threadLocalId.set(id);}
    public static Long getCurrentId(){
        return threadLocalId.get();
    }
    public static void removeCurrentId(){
        threadLocalId.remove();
    }
    //获取，设置，删除当前线程用户类型
    public static void setCurrentRole(UserRoleEnum role){
        threadLocalRole.set(role);
    }
    public static UserRoleEnum getCurrentRole(){
        return threadLocalRole.get();
    }
    public static void removeCurrentRole(){
        threadLocalRole.remove();
    }
    //判断当前线程用户id时候为空
    public static boolean isCurrentIdNull(){
        return threadLocalId.get() == null;
    }
}
