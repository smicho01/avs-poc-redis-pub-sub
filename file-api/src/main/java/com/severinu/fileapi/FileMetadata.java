package com.severinu.fileapi;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "files")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetadata {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private String s3Bucket;

    @Column(nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;
}