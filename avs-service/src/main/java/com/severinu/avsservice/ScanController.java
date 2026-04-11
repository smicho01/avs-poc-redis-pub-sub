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

    @Value("${scan.timeout-seconds}")
    private long timeoutSeconds;

    @GetMapping("/scan")
    public DeferredResult<ScanResult> scan(
            @RequestParam String s3Bucket,
            @RequestParam String s3Key,
            @RequestParam String fileName) {

        String correlationId = UUID.randomUUID().toString();
        long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds);

        DeferredResult<ScanResult> deferredResult = new DeferredResult<>(timeoutMs);
        registry.register(correlationId, deferredResult);

        deferredResult.onTimeout(() -> {
            log.warn("Scan timed out. correlationId=[{}] fileName=[{}]", correlationId, fileName);
            registry.remove(correlationId);
            deferredResult.setErrorResult(new ScanResult(false, 5004, "Scan timed out"));
        });

        deferredResult.onCompletion(() ->
                log.info("Scan completed. correlationId=[{}] fileName=[{}]", correlationId, fileName)
        );

        log.info("Scan request received. correlationId=[{}] s3Bucket=[{}] s3Key=[{}] fileName=[{}]",
                correlationId, s3Bucket, s3Key, fileName);

        externalMalwareScannerUploadService.copyToExternalMalwareScanner(s3Bucket, s3Key, correlationId);

        return deferredResult;
    }
}
