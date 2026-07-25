package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.constant.MessageConstant;
import lombok.Data;

@Data
public class Result <T>{
    private String code;
    private String message;
    private T data;

    public  static<T>  Result<T>   success(T data){
                 Result<T> result=new Result<>();
                 result.setCode(MessageConstant.SUCCESS_CODE);
                 result.setMessage(MessageConstant.SUCCESS_MESSAGE);
                 result.setData(data);
                 return result;
    }
    public  static<T>  Result<T>   success(){
        Result<T> result=new Result<>();
        result.setCode(MessageConstant.SUCCESS_CODE);
        result.setMessage(MessageConstant.SUCCESS_MESSAGE);
        return result;
    }
    public  static<T>  Result<T>   error(String message){
        Result<T> result=new Result<>();
        result.setCode(MessageConstant.ERROR_CODE);
        result.setMessage(message);
        return result;
    }
    public  static<T>  Result<T>   error(){
        Result<T> result=new Result<>();
        result.setCode(MessageConstant.ERROR_CODE);
        result.setMessage(MessageConstant.ERROR_MESSAGE);
        return result;
    }
}
