package io.github.learnerview.simplydone4j.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final DemoCatalogService catalog;
    private final InventoryService inventory;

    public InventoryController(DemoCatalogService catalog, InventoryService inventory) {
        this.catalog = catalog;
        this.inventory = inventory;
    }

    @GetMapping
    public List<DemoCatalogService.Product> products() {
        return catalog.products();
    }

    @PostMapping("/{sku}/restock")
    public DemoCatalogService.Product restock(@PathVariable String sku,
                                              @Valid @RequestBody RestockRequest request) {
        DemoCatalogService.Product product = catalog.require(sku);
        inventory.restock(product.sku(), request.quantity());
        return catalog.require(sku);
    }

    public record RestockRequest(@Min(1) @Max(1000) int quantity) {
    }
}
