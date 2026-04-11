package com.severinu.fileapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AvsClient {

    private final RestTemplate restTemplate;

    @Value("${avs.url}")
    private String avsUrl;

    public AvsClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ScanResult scan(String s3Bucket, String s3Key, String fileName, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(avsUrl + "/scan")
                .queryParam("s3Bucket", s3Bucket)
                .queryParam("s3Key", s3Key)
                .queryParam("fileName", fileName)
                .toUriString();

        return restTemplate.getForObject(url, ScanResult.class);
    }
}