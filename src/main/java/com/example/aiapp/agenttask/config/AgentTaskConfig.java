package com.example.aiapp.agenttask.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableScheduling
public class AgentTaskConfig {

    @Bean("agentTaskExecutor")
    Executor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-task-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "agentLeaseScheduler", destroyMethod = "shutdown")
    ScheduledExecutorService agentLeaseScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "agent-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
