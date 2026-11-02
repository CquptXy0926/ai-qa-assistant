package com.itheima.qa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 本地文件存储：./data/files/{kbId}/{uuid}_{fileName}
 */
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(@Value("${app.storage-dir}") String storageDir) throws IOException {
        this.root = Paths.get(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public String save(long kbId, String fileName, byte[] data) throws IOException {
        String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path dir = root.resolve(String.valueOf(kbId));
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + "_" + safeName);
        Files.write(target, data);
        return target.toString();
    }

    public byte[] read(String storagePath) throws IOException {
        Path p = Paths.get(storagePath).toAbsolutePath().normalize();
        if (!p.startsWith(root)) {
            throw new IOException("非法存储路径");
        }
        return Files.readAllBytes(p);
    }
}
