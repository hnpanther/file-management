package com.hnp.filemanagement.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * How the application reaches stored bytes.
 *
 * <p>The signature is path-shaped: {@code address} is a relative directory, and version and
 * extension are separate arguments because a disk layout builds the file name out of them. An
 * object store has neither - it has one opaque key - so this interface cannot express an S3
 * object without distortion.
 *
 * <p>Do not add an S3 method here. Phase 4 replaces the interface with a key-shaped port; the
 * design is in {@code docs/target-architecture.md}, section "The storage port".
 */
public interface FileStorageService {



    void save(String address, MultipartFile file, int version, String extension);

    public Resource load(String address, String fileName, int version, String extension);

    public void delete(String address, String fileName, int version, String extension, boolean isFile);

    public void createDirectory(String title, boolean isSubDirectory);


}
