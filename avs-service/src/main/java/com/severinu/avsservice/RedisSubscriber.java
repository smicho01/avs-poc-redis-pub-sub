package com.severinu.avsservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

    private final PendingScanRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ScanResultPayload payload = objectMapper.readValue(message.getBody(), ScanResultPayload.class);

            log.info("Received Redis scan result. correlationId=[{}] clean=[{}]",
                    payload.getCorrelationId(), payload.isClean());

            registry.complete(payload.getCorrelationId(), payload.toScanResult());

        } catch (Exception e) {
            log.error("Failed to process Redis message", e);
        }
    }
}
