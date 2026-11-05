package io.github.learnerview.simplydone4j.demo;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DemoCatalogService {

    private static final Map<String, ProductDefinition> DEFINITIONS = definitions();

    private final InventoryService inventory;

    public DemoCatalogService(InventoryService inventory) {
        this.inventory = inventory;
    }

    public List<Product> products() {
        return DEFINITIONS.values().stream()
                .map(this::toProduct)
                .toList();
    }

    public Product require(String sku) {
        ProductDefinition definition = DEFINITIONS.get(sku);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown product SKU: " + sku);
        }
        return toProduct(definition);
    }

    public boolean exists(String sku) {
        return DEFINITIONS.containsKey(sku);
    }

    public long lowStockCount(int threshold) {
        return DEFINITIONS.values().stream()
                .filter(definition -> inventory.stock(definition.sku()) <= threshold)
                .count();
    }

    private Product toProduct(ProductDefinition definition) {
        return new Product(definition.sku(), definition.name(), definition.price(),
                definition.category(), inventory.stock(definition.sku()));
    }

    private static Map<String, ProductDefinition> definitions() {
        Map<String, ProductDefinition> products = new LinkedHashMap<>();
        products.put("cloud-runner", new ProductDefinition("cloud-runner", "Cloud Runner shoes", 129, "Sportswear"));
        products.put("desk-lamp", new ProductDefinition("desk-lamp", "Northstar desk lamp", 64, "Home office"));
        products.put("travel-mug", new ProductDefinition("travel-mug", "Thermal travel mug", 28, "Accessories"));
        products.put("weekend-pack", new ProductDefinition("weekend-pack", "Weekend travel pack", 88, "Travel"));
        products.put("studio-headphones", new ProductDefinition("studio-headphones", "Studio headphones", 159, "Electronics"));
        products.put("trail-bottle", new ProductDefinition("trail-bottle", "Insulated trail bottle", 22, "Outdoor"));
        return Collections.unmodifiableMap(products);
    }

    private record ProductDefinition(String sku, String name, int price, String category) {
    }

    public record Product(String sku, String name, int price, String category, int stock) {
    }
}
