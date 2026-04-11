package com.severinu.avsservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanCacheService {

    // Redis key pattern: scan:cache:{fileId}
    // Separate from pub/sub channels which use scan:result:{correlationId}
    private static final String CACHE_KEY_PREFIX = "scan:cache:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${scan.cache-ttl-seconds}")
    private long cacheTtlSeconds;

    /**
     * Looks up a previously cached scan result for this fileId.
     * Returns null if no cached result exists or if it has expired.
     */
    public ScanResult get(String fileId) {
        String key = CACHE_KEY_PREFIX + fileId;
        String cached = redisTemplate.opsForValue().get(key);

        if (cached == null) {
            return null;
        }

        try {
            ScanResult result = objectMapper.readValue(cached, ScanResult.class);
            log.info("Cache HIT for fileId=[{}]", fileId);
            return result;
        } catch (Exception e) {
            log.error("Failed to deserialize cached scan result for fileId=[{}]", fileId, e);
            return null;
        }
    }

    /**
     * Stores a scan result in Redis with a TTL.
     * After TTL expires, the next request for this file will trigger a fresh scan.
     */
    public void put(String fileId, ScanResult result) {
        String key = CACHE_KEY_PREFIX + fileId;

        try {
            String value = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(cacheTtlSeconds));
            log.info("Cached scan result for fileId=[{}] ttl=[{}s] clean=[{}]",
                    fileId, cacheTtlSeconds, result.clean());
        } catch (Exception e) {
            log.error("Failed to cache scan result for fileId=[{}]", fileId, e);
        }
    }
}