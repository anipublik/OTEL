package com.example.payments.controller;

import com.example.payments.model.Order;
import com.example.payments.service.OrderService;
import com.example.payments.service.OrderService.OrderNotFoundException;
import com.example.payments.service.OrderService.PaymentDeclinedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/orders")
    public Order createOrder(@RequestBody CreateOrderRequest request) {
        return orderService.createOrder(
                request.customerId(),
                request.item(),
                request.amountUsd()
        );
    }

    @GetMapping("/api/orders/{id}")
    public Order getOrder(@PathVariable("id") String id) {
        return orderService.getOrder(id);
    }

    @PostMapping("/api/orders/{id}/pay")
    public Order payOrder(@PathVariable("id") String id) throws Exception {
        return orderService.processPayment(id);
    }

    @PostMapping("/api/orders/{id}/fail")
    public Order failOrder(@PathVariable("id") String id) throws Exception {
        return orderService.simulateFailure(id);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<Map<String, String>> paymentDeclined(PaymentDeclinedException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("error", e.getMessage()));
    }

    public record CreateOrderRequest(String customerId, String item, double amountUsd) {}
}
