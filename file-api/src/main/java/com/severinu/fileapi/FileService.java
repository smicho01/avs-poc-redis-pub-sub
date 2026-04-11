package com.severinu.fileapi;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;
    private final S3Service s3Service;
    private final AvsClient avsClient;

    @Value("${aws.s3.bucket}")
    private String bucket;

    public UploadResponse upload(MultipartFile file) throws IOException {
        UUID fileId = UUID.randomUUID();
        String s3Key = fileId + "/" + file.getOriginalFilename();
        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        s3Service.upload(s3Key, file);

        FileMetadata metadata = FileMetadata.builder()
                .id(fileId)
                .fileName(file.getOriginalFilename())
                .s3Key(s3Key)
                .s3Bucket(bucket)
                .mimeType(mimeType)
                .uploadedAt(LocalDateTime.now())
                .build();

        fileRepository.save(metadata);

        return new UploadResponse(fileId, file.getOriginalFilename(), "File uploaded successfully");
    }

    public ScanResult scanFile(UUID fileId) {
        FileMetadata metadata = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        return avsClient.scan(metadata.getS3Bucket(), metadata.getS3Key(), metadata.getFileName(), fileId);
    }

    public FileMetadata getMetadata(UUID fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));
    }

    public ResponseInputStream<GetObjectResponse> downloadFile(FileMetadata metadata) {
        return s3Service.download(metadata.getS3Bucket(), metadata.getS3Key());
    }
}