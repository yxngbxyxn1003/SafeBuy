package com.safebuy.dto;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class ProductSearchResponse {
    private boolean found;           // 검색 결과 여부
    private String productName;      // 제품명
    private String defectContent;    // 결함내용
    private String manufacturer;     // 제조사
    private String publicationDate;  // 공표일
    private String message;          // 메시지 (검색 결과 없을 때)
}
