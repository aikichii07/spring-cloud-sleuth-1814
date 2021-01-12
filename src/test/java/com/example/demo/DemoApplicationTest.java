package com.example.demo;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;

@SpringBootTest
class DemoApplicationTest {
    private static Logger logger = LoggerFactory.getLogger(DemoApplicationTest.class);

    @Autowired
    @Qualifier("tracingExecutorService")
    ExecutorService tracingExecutorService;

    @Test
    public void testSpringCloudSleuth1814() throws InterruptedException {
        // force child executions to have a parent
        tracingExecutorService.execute(() -> {
            // trace (parent) exists here so branch BraveTracer.java:52 is taken
            for (int i = 0; i < 100; i++) {
                tracingExecutorService.execute(() -> {
                    // trace has parent, which also has a parent
                    logger.info("current MDC = {}", MDC.getCopyOfContextMap());
                });
            }
        });

        while (!tracingExecutorService.isTerminated()) {
            Thread.sleep(1000);
        }
    }
}
