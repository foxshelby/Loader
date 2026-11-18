package com.cpuload;

import java.util.Locale;

/** 可选的 CPU 负载类型。 */
enum WorkloadType {
    MIX("mix", "混合整数/浮点运算"),
    INT("int", "整数位运算"),
    FLOAT("float", "浮点三角函数"),
    MEM("mem", "内存读写"),
    CRYPTO("crypto", "SHA-256 摘要");

    final String id;
    final String description;

    WorkloadType(String id, String description) {
        this.id = id;
        this.description = description;
    }

    static WorkloadType parse(String raw) throws ConfigException {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "mix":
            case "mixed":
            case "default":
                return MIX;
            case "int":
            case "integer":
            case "bit":
            case "long":
                return INT;
            case "float":
            case "fp":
            case "double":
            case "math":
                return FLOAT;
            case "mem":
            case "memory":
            case "bandwidth":
                return MEM;
            case "crypto":
            case "sha":
            case "sha256":
                return CRYPTO;
            default:
                throw new ConfigException("未知的负载类型 \"" + raw
                        + "\"，可选值: mix, int, float, mem, crypto");
        }
    }
}
