package com.ai_photo.net;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 统一响应包：{code, message, data}。
 *
 * code == 200 表示业务成功；data 由各接口自行转换为具体 DTO。
 * 非 200 时 message 字段是错误描述。
 */
public class ApiResponse {

    public final int code;
    public final String message;
    /** 可能是 JSONObject / JSONArray / null / Boolean / Number */
    @Nullable
    public final Object data;

    public ApiResponse(int code, String message, @Nullable Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public boolean isSuccess() {
        return code == 200;
    }

    @Nullable
    public JSONObject dataAsObject() {
        return data instanceof JSONObject ? (JSONObject) data : null;
    }

    @Nullable
    public JSONArray dataAsArray() {
        return data instanceof JSONArray ? (JSONArray) data : null;
    }
}