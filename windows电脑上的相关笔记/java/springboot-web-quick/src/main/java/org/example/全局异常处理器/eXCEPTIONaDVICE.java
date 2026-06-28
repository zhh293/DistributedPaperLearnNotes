package org.example.全局异常处理器;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/*
@RestControllerAdvice
public class eXCEPTIONaDVICE {
    @ExceptionHandler(SystemException.class)
    public Result systemExceptionHandler(SystemException e)
    {
        return new Result(e.getCode(),null,e.getMessage());
    }
    @ExceptionHandler(BusinessException.class)
    public Result businessExceptionHandler(BusinessException e)
    {
        return new Result(e.getCode(),null,e.getMessage());
    }

}
*/
