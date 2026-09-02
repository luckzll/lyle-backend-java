package com.example.aiapp.agenttask.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
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

    @Bean("agentSubtaskExecutor")
    Executor agentSubtaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(30);
        executor.setThreadNamePrefix("agent-subtask-");
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
