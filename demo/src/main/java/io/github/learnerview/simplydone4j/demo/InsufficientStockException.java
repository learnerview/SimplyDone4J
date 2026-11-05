package io.github.learnerview.simplydone4j.demo;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String productName, int requested) {
        super("Not enough stock for " + productName + " (requested " + requested + ")");
    }
}
