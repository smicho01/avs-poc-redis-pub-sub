package com.severinu.fileapi;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface FileRepository extends JpaRepository<FileMetadata, UUID> {
}