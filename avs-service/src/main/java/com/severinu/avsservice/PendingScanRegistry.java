package com.severinu.avsservice;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingScanRegistry {

    private final ConcurrentHashMap<String, DeferredResult<ScanResult>> pending = new ConcurrentHashMap<>();

    public void register(String correlationId, DeferredResult<ScanResult> deferredResult) {
        pending.put(correlationId, deferredResult);
    }

    public void complete(String correlationId, ScanResult result) {
        DeferredResult<ScanResult> deferredResult = pending.remove(correlationId);
        if (deferredResult != null) {
            deferredResult.setResult(result);
        }
    }

    public void remove(String correlationId) {
        pending.remove(correlationId);
    }
}
