package com.safebuy.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.safebuy.dto.AlternativeProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AlternativeProductService {

    private final RestTemplate restTemplate;

    @Value("${shop.client-id}")
    private String clientId;

    @Value("${shop.client-secret}")
    private String clientSecret;

    private static class AlternativeProduct {
        public String title;
        public String brand;
        @JsonProperty("lprice")
        public String price;
        public String image;
        public String link;
        public String category1;
        public String category2;
        public String category3;
        public String category4;
    }

    private static class AlternativeProductResponse {
        public List<AlternativeProduct> items;
    }

    public List<AlternativeProductDto> findAlternatives(String dbCategory, String dbBrand) {
        String url = "https://openapi.naver.com/v1/search/shop.json"
                + "?query=" + dbCategory
                + "&display=100"
                + "&sort=sim"
                + "&exclude=used:rental:cbshop";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Naver-Client-Id", clientId);
        headers.set("X-Naver-Client-Secret", clientSecret);

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<AlternativeProductResponse> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, entity, AlternativeProductResponse.class);
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().items == null) {
            return List.of();
        }

        return response.getBody().items.stream()
                // 브랜드가 존재하고, 리콜 상품의 기존 브랜드와 상이
                .filter(item -> item.brand != null && !item.brand.isEmpty() && !dbBrand.equals(item.brand))
                // 카테고리 일치
                .filter(item -> dbCategory.contains(item.category1)
                        || dbCategory.contains(item.category2)
                        || dbCategory.contains(item.category3)
                        || dbCategory.contains(item.category4))
                .map(item -> {
                    AlternativeProductDto dto = new AlternativeProductDto();
                    dto.setTitle(item.title.replaceAll("<[^>]*>", ""));
                    dto.setBrand(item.brand);
                    dto.setPrice(item.price.replaceAll("(\\d)(?=(\\d{3})+$)", "$1,") + "원");
                    dto.setImage(item.image);
                    dto.setLink(item.link);
                    return dto;
                }).toList();
    }
}
