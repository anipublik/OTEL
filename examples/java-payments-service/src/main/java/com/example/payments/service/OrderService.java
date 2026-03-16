package com.example.payments.service;

import com.example.payments.model.Order;
import com.example.payments.model.Order.OrderStatus;
import com.example.payments.telemetry.OrderMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderMetrics metrics;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public OrderService(OrderMetrics metrics) {
        this.metrics = metrics;
    }

    public Order createOrder(String customerId, String item, double amountUsd) {
        try {
            Order order = Order.create(customerId, item, amountUsd);
            orders.put(order.id(), order);
            metrics.recordOrderCreated(true);
            log.info("Order created id={} customer={} amount={}", order.id(), customerId, amountUsd);
            return order;
        } catch (RuntimeException e) {
            metrics.recordOrderCreated(false);
            throw e;
        }
    }

    public Order getOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    public Order processPayment(String orderId) throws Exception {
        Order order = getOrder(orderId);
        if (order.status() != OrderStatus.PENDING) {
            throw new IllegalStateException("Order already processed: " + order.status());
        }

        long startMs = System.currentTimeMillis();
        try (OrderMetrics.ActiveCheckout checkout = metrics.trackCheckout()) {
            Order paid = metrics.inPaymentSpan(
                    orderId,
                    order.amountUsd(),
                    "card",
                    () -> simulatePaymentGateway(order)
            );
            orders.put(orderId, paid);
            long elapsed = System.currentTimeMillis() - startMs;
            metrics.recordCheckoutDuration(elapsed, true, "card");
            log.info("Payment succeeded order={} durationMs={}", orderId, elapsed);
            return paid;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            metrics.recordCheckoutDuration(elapsed, false, "card");
            throw e;
        }
    }

    public Order simulateFailure(String orderId) throws Exception {
        Order order = getOrder(orderId);
        long startMs = System.currentTimeMillis();
        try (OrderMetrics.ActiveCheckout checkout = metrics.trackCheckout()) {
            metrics.inPaymentSpan(orderId, order.amountUsd(), "card", () -> {
                Thread.sleep(50 + ThreadLocalRandom.current().nextInt(150));
                throw new PaymentDeclinedException("Card declined for order " + orderId);
            });
            throw new IllegalStateException("unreachable");
        } catch (PaymentDeclinedException e) {
            Order failed = order.withStatus(OrderStatus.FAILED);
            orders.put(orderId, failed);
            metrics.recordCheckoutDuration(System.currentTimeMillis() - startMs, false, "card");
            log.warn("Payment failed order={}: {}", orderId, e.getMessage());
            return failed;
        }
    }

    private Order simulatePaymentGateway(Order order) throws InterruptedException {
        // Simulate variable gateway latency — shows up in the checkout.duration histogram.
        Thread.sleep(80 + ThreadLocalRandom.current().nextInt(320));
        return order.withStatus(OrderStatus.PAID);
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String orderId) {
            super("Order not found: " + orderId);
        }
    }

    public static class PaymentDeclinedException extends Exception {
        public PaymentDeclinedException(String message) {
            super(message);
        }
    }
}
