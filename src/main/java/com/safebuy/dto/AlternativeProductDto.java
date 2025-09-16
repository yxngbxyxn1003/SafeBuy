package com.safebuy.dto;

import lombok.Getter;
import lombok.Setter;

// 대체 상품 정보
@Getter
@Setter
public class AlternativeProductDto {

    // 제목
    private String title;

    // 제조사
    private String maker;

    // 가격
    private String price;

    // 이미지 URL
    private String image;

    // 링크
    private String link;
}