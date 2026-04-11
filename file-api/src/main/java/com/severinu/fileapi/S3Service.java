package com.severinu.fileapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

@Service
public class S3Service {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucket;

    public S3Service(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public void upload(String s3Key, MultipartFile file) throws IOException {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
    }

    public ResponseInputStream<GetObjectResponse> download(String s3Bucket, String s3Key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Bucket)
                .key(s3Key)
                .build();

        return s3Client.getObject(request);
    }
}