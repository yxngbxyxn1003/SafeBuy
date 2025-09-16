package com.safebuy.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.safebuy.dto.AlternativeProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

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

    public List<AlternativeProductDto> findAlternatives(String productName, String dbCategory, String dbBrand) {
        if (productName == null || productName.isBlank()) {
            return List.of();
        }

        // 제품명의 전체 문자열로 검색
        List<AlternativeProductDto> results = searchByKeyword(productName, dbCategory, dbBrand);
        if (!results.isEmpty()) {
            return results; // 검색 결과가 존재하는 경우, 반환
        }

        // 전체 문자열 검색 결과가 존재하지 않는 경우
        String[] keywords = productName.split("\\s+");
        Set<String> seenLinks = new HashSet<>();
        List<AlternativeProductDto> finalResults = new ArrayList<>();

        for (String keyword : keywords) {
            List<AlternativeProductDto> partialResults = searchByKeyword(keyword, dbCategory, dbBrand);
            for (AlternativeProductDto dto : partialResults) {
                if (seenLinks.contains(dto.getLink())) continue;
                seenLinks.add(dto.getLink());
                finalResults.add(dto);
            }
        }

        return finalResults;
    }

    // 공통 검색
    private List<AlternativeProductDto> searchByKeyword(String keyword, String dbCategory, String dbBrand) {
        String url = "https://openapi.naver.com/v1/search/shop.json"
                + "?query=" + keyword
                + "&display=100"
                + "&sort=sim"
                + "&exclude=used:rental:cbshop";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Naver-Client-Id", clientId);
        headers.set("X-Naver-Client-Secret", clientSecret);

        HttpEntity<String> entity = new HttpEntity<>(headers);

        AlternativeProductResponse response;
        try {
            ResponseEntity<AlternativeProductResponse> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, AlternativeProductResponse.class
            );
            response = resp.getBody();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }

        if (response == null || response.items == null) return List.of();

        List<AlternativeProductDto> results = new ArrayList<>();
        for (AlternativeProduct item : response.items) {
            if (item.brand == null || item.brand.isEmpty() || dbBrand.equals(item.brand)) continue;

            if (dbCategory != null && !dbCategory.isBlank() && !dbCategory.equals("기타")) {
                if (!(item.category1.contains(dbCategory) ||
                        item.category2.contains(dbCategory) ||
                        item.category3.contains(dbCategory) ||
                        item.category4.contains(dbCategory))) continue;
            }

            AlternativeProductDto dto = new AlternativeProductDto();
            dto.setTitle(item.title.replaceAll("<[^>]*>", ""));
            dto.setBrand(item.brand);
            dto.setPrice(item.price.replaceAll("(\\d)(?=(\\d{3})+$)", "$1,") + "원");
            dto.setImage(item.image);
            dto.setLink(item.link);

            results.add(dto);
        }

        return results;
    }
}