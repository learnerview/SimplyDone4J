package io.github.learnerview.simplydone4j.demo;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public OrderRecord place(@Valid @RequestBody PlaceOrderRequest request) {
        return orders.place(request);
    }

    @GetMapping
    public List<OrderRecord> recent() {
        return orders.recent();
    }

    @GetMapping("/{orderId}")
    public OrderRecord find(@PathVariable String orderId) {
        return orders.find(orderId);
    }

    @DeleteMapping("/{orderId}")
    public OrderRecord cancel(@PathVariable String orderId) {
        return orders.cancel(orderId);
    }
}
