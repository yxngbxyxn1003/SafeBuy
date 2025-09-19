package com.safebuy.service;

import com.safebuy.dto.AlternativeProductDto;
import com.safebuy.dto.ProductSearchRequest;
import com.safebuy.dto.ProductSearchResponse;
import com.safebuy.entity.RecallProduct;
import com.safebuy.repository.RecallProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSearchService {

    private final RecallProductRepository repository;
    private final ImageAnalysisService imageAnalysisService;
    private final AlternativeProductService alternativeProductService;

    public ProductSearchResponse searchProduct(ProductSearchRequest request) {
        log.info("제품 검색 요청: {}", request);
        
        // 입력값 검증 - 최소 하나 이상의 필드가 입력되어야 함
        if (!isValidInput(request)) {
            return ProductSearchResponse.builder()
                    .found(false)
                    .message("최소 하나 이상의 정보를 입력해주세요.")
                    .build();
        }

        // 이미지가 있는 경우 AI 분석 수행
        if (request.getImage() != null && !request.getImage().isEmpty()) {
            String analysisResult = imageAnalysisService.analyzeImage(request.getImage());
            if (analysisResult != null) {
                parseAnalysisResult(analysisResult, request);
            }
        }

        // 순차적 검색 수행
        RecallProduct foundProduct = performSequentialSearch(request);
        
        if (foundProduct != null) {
            ProductSearchResponse response = ProductSearchResponse.builder()
                    .found(true)
                    .productName(foundProduct.getProductNm())
                    .defectContent(foundProduct.getShrtcomCn())
                    .manufacturer(foundProduct.getMakr())
                    .publicationDate(foundProduct.getRecallPublictBgnde())
                    .build();

            // 대체 상품 추천
            List<AlternativeProductDto> alternatives =
                    alternativeProductService.findAlternatives(foundProduct.getProductNm(),
                            foundProduct.getCategory(),
                            foundProduct.getMakr());
            response.setAlternatives(alternatives);

            return response;
        } else {
            return ProductSearchResponse.builder()
                    .found(false)
                    .message("해당 제품은 리콜데이터에 해당하는 검색 결과가 없습니다.")
                    .build();
        }
    }

    private boolean isValidInput(ProductSearchRequest request) {
        return StringUtils.hasText(request.getProductName()) ||
               StringUtils.hasText(request.getManufacturer()) ||
               StringUtils.hasText(request.getModelName()) ||
               (request.getImage() != null && !request.getImage().isEmpty());
    }

    private void parseAnalysisResult(String analysisResult, ProductSearchRequest request) {
        // AI 분석 결과에서 제품명, 제조사, 모델명 추출
        Pattern productNamePattern = Pattern.compile("제품명:\\s*([^,]+)");
        Pattern manufacturerPattern = Pattern.compile("제조사:\\s*([^,]+)");
        Pattern modelNamePattern = Pattern.compile("모델명:\\s*([^,]+)");

        Matcher productNameMatcher = productNamePattern.matcher(analysisResult);
        Matcher manufacturerMatcher = manufacturerPattern.matcher(analysisResult);
        Matcher modelNameMatcher = modelNamePattern.matcher(analysisResult);

        if (productNameMatcher.find() && !StringUtils.hasText(request.getProductName())) {
            request.setProductName(productNameMatcher.group(1).trim());
        }
        if (manufacturerMatcher.find() && !StringUtils.hasText(request.getManufacturer())) {
            request.setManufacturer(manufacturerMatcher.group(1).trim());
        }
        if (modelNameMatcher.find() && !StringUtils.hasText(request.getModelName())) {
            request.setModelName(modelNameMatcher.group(1).trim());
        }
    }

    private RecallProduct performSequentialSearch(ProductSearchRequest request) {
        // 1단계: 제품명으로만 검색
        if (StringUtils.hasText(request.getProductName())) {
            List<RecallProduct> products = repository.findByProductNmContainingIgnoreCase(request.getProductName());
            if (products.size() == 1) {
                log.info("제품명으로 단일 결과 발견: {}", products.get(0).getProductNm());
                return products.get(0);
            } else if (products.size() > 1) {
                log.info("제품명으로 {}개 결과 발견, 다음 단계 진행", products.size());
            } else {
                log.info("제품명으로 검색 결과 없음");
            }
        }

        // 2단계: 제품명 + 제조사로 검색
        if (StringUtils.hasText(request.getProductName()) && StringUtils.hasText(request.getManufacturer())) {
            List<RecallProduct> products = repository.findByProductNmContainingIgnoreCaseAndMakrContainingIgnoreCase(
                    request.getProductName(), request.getManufacturer());
            if (products.size() == 1) {
                log.info("제품명+제조사로 단일 결과 발견: {}", products.get(0).getProductNm());
                return products.get(0);
            } else if (products.size() > 1) {
                log.info("제품명+제조사로 {}개 결과 발견, 다음 단계 진행", products.size());
            } else {
                log.info("제품명+제조사로 검색 결과 없음");
            }
        }

        // 3단계: 제품명 + 제조사 + 모델명으로 검색
        if (StringUtils.hasText(request.getProductName()) && 
            StringUtils.hasText(request.getManufacturer()) && 
            StringUtils.hasText(request.getModelName())) {
            
            List<RecallProduct> products = repository.findByProductNmContainingIgnoreCaseAndMakrContainingIgnoreCaseAndModlNmInfoContainingIgnoreCase(
                    request.getProductName(), request.getManufacturer(), request.getModelName());
            if (products.size() == 1) {
                log.info("제품명+제조사+모델명으로 단일 결과 발견: {}", products.get(0).getProductNm());
                return products.get(0);
            } else if (products.size() > 1) {
                log.info("제품명+제조사+모델명으로 {}개 결과 발견, 첫 번째 결과 반환", products.size());
                return products.get(0);
            } else {
                log.info("제품명+제조사+모델명으로 검색 결과 없음");
            }
        }

        // 4단계: 제조사만으로 검색 (제품명이 없는 경우)
        if (!StringUtils.hasText(request.getProductName()) && StringUtils.hasText(request.getManufacturer())) {
            List<RecallProduct> products = repository.findByMakrContainingIgnoreCase(request.getManufacturer());
            if (products.size() == 1) {
                log.info("제조사로 단일 결과 발견: {}", products.get(0).getProductNm());
                return products.get(0);
            } else if (products.size() > 1) {
                log.info("제조사로 {}개 결과 발견, 첫 번째 결과 반환", products.size());
                return products.get(0);
            }
        }

        // 5단계: 모델명만으로 검색 (제품명, 제조사가 없는 경우)
        if (!StringUtils.hasText(request.getProductName()) && 
            !StringUtils.hasText(request.getManufacturer()) && 
            StringUtils.hasText(request.getModelName())) {
            
            List<RecallProduct> products = repository.findByModlNmInfoContainingIgnoreCase(request.getModelName());
            if (products.size() == 1) {
                log.info("모델명으로 단일 결과 발견: {}", products.get(0).getProductNm());
                return products.get(0);
            } else if (products.size() > 1) {
                log.info("모델명으로 {}개 결과 발견, 첫 번째 결과 반환", products.size());
                return products.get(0);
            }
        }

        return null;
    }
}
