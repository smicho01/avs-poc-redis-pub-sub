package com.severinu.fileapi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping("/v1/files/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        log.info("Upload request received for file: {}", file.getOriginalFilename());
        UploadResponse response = fileService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/v1/files/{fileId}/content")
    public ResponseEntity<Object> getFileContent(@PathVariable UUID fileId) throws IOException {
        log.info("Download request received for fileId: {}", fileId);

        ScanResult scanResult = fileService.scanFile(fileId);

        if (!scanResult.clean()) {
            log.warn("File {} is infected, blocking download. code={}", fileId, scanResult.code());
            return ResponseEntity
                    .status(HttpStatus.LOCKED)
                    .body(Map.of(
                            "status", "INFECTED",
                            "code", scanResult.code(),
                            "message", scanResult.message()
                    ));
        }

        FileMetadata metadata = fileService.getMetadata(fileId);
        ResponseInputStream<GetObjectResponse> s3Stream = fileService.downloadFile(metadata);

        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(metadata.getFileName())
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(contentDisposition);
        headers.setContentType(MediaType.parseMediaType(metadata.getMimeType()));

        return ResponseEntity.ok()
                .headers(headers)
                .body(s3Stream.readAllBytes());
    }
}