package com.face.api.engine;

/**
 * 人脸引擎初始化、检测或特征处理失败时抛出的业务异常。
 */
public class FaceEngineException extends RuntimeException {
    public FaceEngineException(String message) {
        super(message);
    }

    public FaceEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
