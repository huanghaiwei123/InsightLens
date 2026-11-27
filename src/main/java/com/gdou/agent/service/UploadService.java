package com.gdou.agent.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UploadService {
    String upload(List<MultipartFile> excels);
}
