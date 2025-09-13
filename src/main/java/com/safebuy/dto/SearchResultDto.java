package com.safebuy.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

// 검색 결과 정보
@Getter
@Setter
public class SearchResultDto {

    // 위험 점수 [0, 100]
    private int riskScore;

    // 위험 등급 (고위험: [80, 100], 중위험: [50, 79], 저위험: [1, 50], DB 기록 없음: [0, 0])
    private String riskLevel;

    // 모델명
    private String modelName;

    // 결함 내용
    private String defectDetails;

    // 제조사
    private String manufacturer;

    // 공표일 (YYYY-MM-DD)
    private String announcementDate;

    // 원본 링크
    private String sourceUrl;

    // 대체 상품 리스트
    private List<AlternativeProductDto> alternativeProducts;
}