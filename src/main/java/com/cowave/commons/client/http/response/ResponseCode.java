package com.cowave.commons.client.http.response;

/**
 *
 * @author shanhuiming
 *
 */
public interface ResponseCode {

    /**
     * 状态
     */
    int getStatus();

    /**
     * 响应码
     */
    String getCode();

    /**
     * 响应描述
     */
    String getMsg();
}
