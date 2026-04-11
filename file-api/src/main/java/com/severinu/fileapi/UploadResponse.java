package com.severinu.fileapi;

import java.util.UUID;

public record UploadResponse(UUID fileId, String fileName, String message) {
}
