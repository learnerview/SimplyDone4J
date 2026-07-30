package io.github.learnerview.simplydone4j.handler;

@FunctionalInterface
public interface JobHandler {
    String handle(JobContext context) throws Exception;
}
