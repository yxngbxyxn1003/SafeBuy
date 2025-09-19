package com.safebuy.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class ProductSearchRequest {
    private String productName;    // 제품명
    private String manufacturer;   // 제조사
    private String modelName;      // 모델명
    private MultipartFile image;   // 이미지 파일
}
