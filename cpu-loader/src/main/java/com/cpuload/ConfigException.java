package com.cpuload;

/** 参数/配置错误。 */
class ConfigException extends Exception {
    private static final long serialVersionUID = 1L;

    ConfigException(String message) {
        super(message);
    }
}
