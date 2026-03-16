package com.example.payments.model;

import java.time.Instant;
import java.util.UUID;

public record Order(
        String id,
        String customerId,
        String item,
        double amountUsd,
        OrderStatus status,
        Instant createdAt
) {
    public static Order create(String customerId, String item, double amountUsd) {
        return new Order(
                UUID.randomUUID().toString(),
                customerId,
                item,
                amountUsd,
                OrderStatus.PENDING,
                Instant.now()
        );
    }

    public Order withStatus(OrderStatus newStatus) {
        return new Order(id, customerId, item, amountUsd, newStatus, createdAt);
    }

    public enum OrderStatus {
        PENDING,
        PAID,
        FAILED
    }
}
