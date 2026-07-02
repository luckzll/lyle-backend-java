package com.example.aiapp.infrastructure.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);

    public void publish(String topic, String payload) {
        log.info("Local message published. topic={}, payload={}", topic, payload);
    }
}
