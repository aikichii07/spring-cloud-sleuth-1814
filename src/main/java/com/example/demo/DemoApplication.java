package com.example.demo;

import brave.sampler.CountingSampler;
import brave.sampler.Sampler;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.sleuth.instrument.async.TraceableExecutorService;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    Sampler sampler() {
        // necessary to prevent noop spans brave.Tracer.java:407
        return CountingSampler.create(0.02f);
    }

    @Bean(name = "tracingExecutorService")
    ExecutorService tracingExecutorService(BeanFactory beanFactory) {
        return new TraceableExecutorService(
            beanFactory,
            new ThreadPoolExecutor(
                5,
                5,
                2000, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10000000),
                new BasicThreadFactory.Builder()
                    .namingPattern("bla-%d")
                    .build()
            )
        );
    }
}
