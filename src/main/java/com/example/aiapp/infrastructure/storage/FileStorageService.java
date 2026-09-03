package com.example.aiapp.infrastructure.storage;

import com.example.aiapp.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 通用文件对象存储服务。
 */
@Component
public class FileStorageService {

    private final MinioProperties properties;
    private final MinioClient minioClient;

    public FileStorageService(MinioProperties properties) {
        this.properties = properties;
        this.minioClient = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    public StoredObject upload(String bucketName, String objectName, InputStream inputStream, long size, String contentType) {
        if (properties.enabled()) {
            return uploadToMinio(objectName, inputStream, size, contentType);
        }
        Path root = Path.of(System.getProperty("user.dir")).getParent().resolve("storage");
        Path target = root.resolve(bucketName).resolve(objectName).normalize();

        try {
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredObject(
                    "LOCAL",
                    bucketName,
                    objectName,
                    target.toUri().toString(),
                    contentType == null ? "application/octet-stream" : contentType,
                    size,
                    "private"
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store uploaded file.", exception);
        }
    }

    public InputStream download(String bucketName, String objectName) {
        if (properties.enabled()) {
            try {
                return minioClient.getObject(GetObjectArgs.builder()
                        .bucket(properties.bucket())
                        .object(objectName)
                        .build());
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to read MinIO object.", exception);
            }
        }
        Path root = Path.of(System.getProperty("user.dir")).getParent().resolve("storage");
        Path target = root.resolve(bucketName).resolve(objectName).normalize();

        try {
            return Files.newInputStream(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read stored file.", exception);
        }
    }

    public void delete(String bucketName, String objectName) {
        if (properties.enabled()) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(properties.bucket())
                        .object(objectName)
                        .build());
                return;
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to delete MinIO object.", exception);
            }
        }
        Path root = Path.of(System.getProperty("user.dir")).getParent().resolve("storage").normalize();
        Path target = root.resolve(bucketName).resolve(objectName).normalize();
        if (!target.startsWith(root.resolve(bucketName).normalize())) {
            throw new IllegalArgumentException("Invalid storage object path.");
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete stored file.", exception);
        }
    }

    private StoredObject uploadToMinio(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            return new StoredObject(
                    "MINIO",
                    properties.bucket(),
                    objectName,
                    "s3://" + properties.bucket() + "/" + objectName,
                    contentType == null ? "application/octet-stream" : contentType,
                    size,
                    "private"
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to upload object to MinIO.", exception);
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.bucket())
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.bucket())
                    .build());
        }
    }
}
