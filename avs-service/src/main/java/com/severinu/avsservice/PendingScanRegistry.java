package com.severinu.avsservice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingScanRegistry {

    // Maps correlationId → DeferredResult waiting for scan outcome
    private final ConcurrentHashMap<String, DeferredResult<ScanResult>> pendingResults = new ConcurrentHashMap<>();

    // Maps correlationId → fileId so we know which file to cache the result against
    private final ConcurrentHashMap<String, String> correlationToFileId = new ConcurrentHashMap<>();

    private final ScanCacheService cacheService;

    /**
     * Registers a new pending scan.
     * Called when AVS receives a scan request from file-api.
     */
    public void register(String correlationId, String fileId, DeferredResult<ScanResult> deferredResult) {
        pendingResults.put(correlationId, deferredResult);
        correlationToFileId.put(correlationId, fileId);
    }

    /**
     * Completes a pending scan when the Redis Pub/Sub message arrives.
     * Also writes the result to the shared Redis cache so future requests
     * for the same file skip the external scanner entirely.
     */
    public void complete(String correlationId, ScanResult result) {
        DeferredResult<ScanResult> deferredResult = pendingResults.remove(correlationId);
        String fileId = correlationToFileId.remove(correlationId);

        // Cache the result against fileId so other pods benefit too
        if (fileId != null) {
            cacheService.put(fileId, result);
        }

        // Wake up the waiting HTTP request on whichever pod owns this correlationId
        if (deferredResult != null) {
            deferredResult.setResult(result);
        }
    }

    /**
     * Removes a pending scan entry on timeout.
     * We do not cache timeouts - the next request should trigger a fresh scan.
     */
    public void remove(String correlationId) {
        pendingResults.remove(correlationId);
        correlationToFileId.remove(correlationId);
    }
}