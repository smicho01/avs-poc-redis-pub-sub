package com.severinu.avsservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanResultPayload {
    private String correlationId;
    private boolean clean;
    private int code;
    private String message;

    public ScanResult toScanResult() {
        return new ScanResult(clean, code, message);
    }
}
