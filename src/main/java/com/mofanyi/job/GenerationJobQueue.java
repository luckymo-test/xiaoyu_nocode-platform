package com.mofanyi.job;

import java.util.List;

/**
 * Job 投递端口。默认实现使用事务 Outbox 表；后续可替换为 Redis Streams/RabbitMQ/Kafka。
 */
public interface GenerationJobQueue {

    List<Delivery> pollReady(int limit);

    void acknowledge(Delivery delivery);

    void retry(Delivery delivery, int delaySeconds);

    void enqueue(Long jobId);

    record Delivery(String deliveryId, Long outboxId, Long jobId) {
    }
}
