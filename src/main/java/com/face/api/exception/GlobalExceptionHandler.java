package com.face.api.exception;

import com.face.api.engine.FaceEngineException;
import com.face.api.model.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/** 统一将参数和引擎异常转换为旧项目格式的错误响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(com.face.api.engine.FaceBusyException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<?> handleBusy(Exception exception) {
        return ApiResponse.error(exception.getMessage());
    }
    @ExceptionHandler({
            IllegalArgumentException.class,
            FaceEngineException.class,
            MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.beans.TypeMismatchException.class,
            MultipartException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleBadRequest(Exception exception) {
        return ApiResponse.error(exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleException(Exception exception) {
        return ApiResponse.error("服务器异常");
    }
}
