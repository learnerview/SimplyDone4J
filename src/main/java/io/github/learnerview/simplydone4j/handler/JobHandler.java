package io.github.learnerview.simplydone4j.handler;

@FunctionalInterface
public interface JobHandler {
    void handle(JobContext context) throws Exception;
}
