package com.severinu.avsservice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ScanController {

    private final PendingScanRegistry registry;
    private final ExternalMalwareScannerUploadService externalMalwareScannerUploadService;
    private final ScanCacheService cacheService;

    @Value("${scan.timeout-seconds}")
    private long timeoutSeconds;

    @GetMapping("/scan")
    public DeferredResult<ScanResult> scan(
            @RequestParam String s3Bucket,
            @RequestParam String s3Key,
            @RequestParam String fileName,
            @RequestParam String fileId) {

        long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds);
        DeferredResult<ScanResult> deferredResult = new DeferredResult<>(timeoutMs);

        // Check Redis cache first.
        // If this file was scanned recently the result is already here.
        // No need to upload to external scanner again - respond instantly.
        ScanResult cachedResult = cacheService.get(fileId);
        if (cachedResult != null) {
            log.info("Returning cached scan result. fileId=[{}] clean=[{}]", fileId, cachedResult.clean());
            deferredResult.setResult(cachedResult);
            return deferredResult;
        }

        // Cache miss - this file has not been scanned recently.
        // Register the DeferredResult so the Redis subscriber can complete it
        // when the scan result arrives (possibly on a different pod).
        String correlationId = UUID.randomUUID().toString();
        registry.register(correlationId, fileId, deferredResult);

        deferredResult.onTimeout(() -> {
            log.warn("Scan timed out. correlationId=[{}] fileName=[{}]", correlationId, fileName);
            registry.remove(correlationId);
            deferredResult.setErrorResult(new ScanResult(false, 5004, "Scan timed out"));
        });

        deferredResult.onCompletion(() ->
                log.info("Scan completed. correlationId=[{}] fileName=[{}]", correlationId, fileName)
        );

        log.info("Cache MISS - uploading to external scanner. correlationId=[{}] fileId=[{}] fileName=[{}]",
                correlationId, fileId, fileName);

        // Copy file to external scanner inbox and wait for async result
        externalMalwareScannerUploadService.copyToExternalMalwareScanner(s3Bucket, s3Key, correlationId);

        return deferredResult;
    }
}
