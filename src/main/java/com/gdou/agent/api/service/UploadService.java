package com.gdou.agent.api.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UploadService {
    void upload(List<MultipartFile> excels);
}
