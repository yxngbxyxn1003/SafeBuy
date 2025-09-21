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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    
    private static final String DETAIL_BASE_URL = 
            "https://www.consumer.go.kr/user/ftc/consumer/recallInfo/1077/selectRecallInfoForeignDetail.do";

    public ProductSearchResponse searchProduct(ProductSearchRequest request) {
        log.info("제품 검색 요청: {}", request);
        
        // 이미지 파일 상세 정보 로깅 (모바일 디버깅용)
        if (request.getImage() != null) {
            log.info("이미지 파일 정보 - 이름: {}, 크기: {}, 타입: {}, 비어있음: {}", 
                    request.getImage().getOriginalFilename(),
                    request.getImage().getSize(),
                    request.getImage().getContentType(),
                    request.getImage().isEmpty());
        } else {
            log.warn("이미지 파일이 null입니다 - 모바일 업로드 문제 가능성");
        }
        
        // 입력값 검증 - 최소 하나 이상의 필드가 입력되어야 함
        if (!isValidInput(request)) {
            log.warn("입력값 검증 실패 - 모든 필드가 비어있음");
            return ProductSearchResponse.builder()
                    .found(false)
                    .message("최소 하나 이상의 정보를 입력해주세요.")
                    .build();
        }

        // 이미지가 있는 경우 AI 분석 수행 (모바일 호환성 개선)
        if (isValidImage(request.getImage())) {
            log.info("이미지 분석 시작");
            try {
                String analysisResult = imageAnalysisService.analyzeImage(request.getImage());
                if (analysisResult != null && !analysisResult.trim().isEmpty()) {
                    log.info("이미지 분석 성공: {}", analysisResult);
                    parseAnalysisResult(analysisResult, request);
                } else {
                    log.warn("이미지 분석 결과가 비어있음");
                }
            } catch (Exception e) {
                log.error("이미지 분석 중 오류 발생: {}", e.getMessage(), e);
                // 이미지 분석 실패해도 다른 정보로 검색 계속 진행
            }
        }

        // 순차적 검색 수행
        RecallProduct foundProduct = performSequentialSearch(request);
        
        if (foundProduct != null) {
            log.info("찾은 제품 정보 - recallSn: '{}', productName: '{}', manufacturer: '{}'", 
                    foundProduct.getRecallSn(), foundProduct.getProductNm(), foundProduct.getMakr());
            
            ProductSearchResponse response = ProductSearchResponse.builder()
                    .found(true)
                    .productName(foundProduct.getProductNm())
                    .defectContent(foundProduct.getShrtcomCn())
                    .manufacturer(foundProduct.getMakr())
                    .publicationDate(foundProduct.getRecallPublictBgnde())
                    .detailUrl(buildDetailUrl(foundProduct.getRecallSn()))
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
        boolean hasTextInput = StringUtils.hasText(request.getProductName()) ||
                              StringUtils.hasText(request.getManufacturer()) ||
                              StringUtils.hasText(request.getModelName());
        boolean hasValidImage = isValidImage(request.getImage());
        
        log.info("입력값 검증 - 텍스트 입력: {}, 유효한 이미지: {}", hasTextInput, hasValidImage);
        
        return hasTextInput || hasValidImage;
    }
    
    private boolean isValidImage(org.springframework.web.multipart.MultipartFile image) {
        if (image == null) {
            return false;
        }
        
        // 파일이 비어있는지 확인
        if (image.isEmpty()) {
            log.warn("이미지 파일이 비어있음");
            return false;
        }
        
        // 파일 크기 확인 (최대 30MB)
        long maxSize = 30 * 1024 * 1024; // 30MB
        if (image.getSize() > maxSize) {
            log.warn("이미지 파일 크기가 너무 큼: {} bytes", image.getSize());
            return false;
        }
        
        // 파일 타입 확인 (모바일 호환성 개선)
        String contentType = image.getContentType();
        if (contentType != null) {
            boolean isValidType = contentType.startsWith("image/") ||
                                 contentType.equals("application/octet-stream"); // 모바일에서 가끔 이 타입으로 전송됨
            if (!isValidType) {
                log.warn("지원하지 않는 파일 타입: {}", contentType);
                return false;
            }
        }
        
        // 파일명 확인
        String originalFilename = image.getOriginalFilename();
        if (originalFilename != null && !originalFilename.trim().isEmpty()) {
            log.info("이미지 파일 검증 성공 - 이름: {}, 크기: {}, 타입: {}", 
                    originalFilename, image.getSize(), contentType);
        }
        
        return true;
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

  
    private String buildDetailUrl(String recallSn) {
        log.info("buildDetailUrl 호출 - recallSn: '{}'", recallSn);
        
        if (!StringUtils.hasText(recallSn)) {
            log.warn("recallSn이 null이거나 빈 문자열입니다: '{}'", recallSn);
            return null;
        }
        
        try {
            String encodedRecallSn = URLEncoder.encode(recallSn, StandardCharsets.UTF_8);
            String detailUrl = DETAIL_BASE_URL + "?recallSn=" + encodedRecallSn;
            log.info("상세 URL 생성 성공: {}", detailUrl);
            return detailUrl;
        } catch (Exception e) {
            log.warn("상세 URL 생성 실패: recallSn={}", recallSn, e);
            return null;
        }
    }
}
