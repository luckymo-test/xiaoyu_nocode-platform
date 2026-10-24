package com.mofanyi.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Web Research 供应商协议边界。缓存、限流、URL 校验和响应清洗留在业务网关中。
 */
public interface WebResearchTransport {

    String id();

    JsonNode post(String path, Map<String, Object> body);
}
