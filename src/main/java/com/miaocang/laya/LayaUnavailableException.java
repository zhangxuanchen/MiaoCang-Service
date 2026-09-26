package com.miaocang.laya;

/** 侧车不可用（未就绪、超时、非 2xx）。调用方一律 fail-open，不重试。 */
public class LayaUnavailableException extends RuntimeException {

    public LayaUnavailableException(String message) {
        super(message);
    }

    public LayaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}