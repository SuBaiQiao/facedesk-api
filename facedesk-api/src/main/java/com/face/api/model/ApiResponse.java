package com.face.api.model;

import java.util.List;

/** 与参考项目兼容的统一接口响应。 */
public class ApiResponse<T> {
    private String code;
    private String msg;
    private Long total;
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(String code, String msg, Long total, T data) {
        this.code = code;
        this.msg = msg;
        this.total = total;
        this.data = data;
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>("200", "操作成功", null, null);
    }

    public static <T> ApiResponse<T> success(T data) {
        Long total = data instanceof List ? (long) ((List<?>) data).size() : null;
        return new ApiResponse<>("200", "操作成功", total, data);
    }

    public static <T> ApiResponse<T> success(T data, long total) {
        return new ApiResponse<>("200", "操作成功", total, data);
    }

    public static <T> ApiResponse<T> error(String msg) {
        String message = msg == null || msg.isEmpty() ? "操作失败，请联系管理员" : msg;
        return new ApiResponse<>("500", message, null, null);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
