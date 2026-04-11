package com.severinu.avsservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqsConsumer {

    private static final String REDIS_CHANNEL_PREFIX = "scan:result:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @SqsListener("${aws.sqs.queue-name}")
    public void onMessage(String rawMessage) {
        try {
            ScanResultPayload payload = unwrapSnsEnvelope(rawMessage);
            String channel = REDIS_CHANNEL_PREFIX + payload.getCorrelationId();

            log.info("Publishing scan result to Redis. correlationId=[{}] clean=[{}] code=[{}]",
                    payload.getCorrelationId(), payload.isClean(), payload.getCode());

            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(payload));

        } catch (Exception e) {
            log.error("Failed to process SQS message. message=[{}]", rawMessage, e);
        }
    }

    private ScanResultPayload unwrapSnsEnvelope(String rawMessage) throws Exception {
        JsonNode root = objectMapper.readTree(rawMessage);

        if (root.has("Type") && "Notification".equals(root.get("Type").asText())) {
            String innerMessage = root.get("Message").asText();
            return objectMapper.readValue(innerMessage, ScanResultPayload.class);
        }

        return objectMapper.readValue(rawMessage, ScanResultPayload.class);
    }
}