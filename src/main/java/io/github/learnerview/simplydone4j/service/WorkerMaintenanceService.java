package io.github.learnerview.simplydone4j.service;

public interface WorkerMaintenanceService {
    void promoteRetries();
    void recoverExpiredLeases();
}
