package com.mofanyi.core;

import java.util.Map;

/**
 * Browser Worker 传输端口。HTTP 用于同步回退，Redis Streams 用于异步执行。
 */
public interface BrowserVerificationTransport {

    String execute(Map<String, Object> request);
}
