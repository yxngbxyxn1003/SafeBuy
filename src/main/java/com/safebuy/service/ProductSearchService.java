package com.safebuy.service;

import com.safebuy.dto.AlternativeProductDto;
import com.safebuy.dto.ProductSearchRequest;
import com.safebuy.dto.ProductSearchResponse;
import com.safebuy.entity.RecallProduct;
import com.safebuy.repository.RecallProductRepository;
import com.safebuy.util.RiskEvaluator;
import com.safebuy.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
    private final SearchQueryEnhancerService searchQueryEnhancerService; // AI 검색어 확장 서비스 주입

    private static final String DETAIL_BASE_URL =
            "https://www.consumer.go.kr/user/ftc/consumer/recallInfo/1077/selectRecallInfoForeignDetail.do";


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

        // 정규화 단계
        String normalizedProductName = TextNormalizer.normalizeText(request.getProductName());
        String normalizedManufacturer = TextNormalizer.normalizeManufacturer(request.getManufacturer());
        String normalizedModelName = TextNormalizer.normalizeText(request.getModelName());

        // 검색어 확장 (정규화 된 문자열 기반)
        List<String> expandedProductNames = expandIfNotBlank(normalizedProductName);
        List<String> expandedManufacturers = expandIfNotBlank(normalizedManufacturer);
        List<String> expandedModels = expandIfNotBlank(normalizedModelName);

        // 후보 조합 리스트 생성
        List<SearchCandidate> candidates =
                buildSearchCandidates(expandedProductNames, expandedManufacturers, expandedModels);

        // 단계적 DB 검색 + (변경) 필드별 위험도 계산 호출
        for (SearchCandidate candidate : candidates) {
            RecallProduct foundProduct = performSequentialSearch(candidate);
            if (foundProduct != null) {
                // (변경 사항) 이전에는 candidate.toString()으로 한 문장 비교 → 매칭 실패 원인
                //     → 필드별로 분리해서 RiskEvaluator에 전달 (모델/제품/제조사 각각 독립 가중치)
                int riskScore = RiskEvaluator.calculateRiskScore(
                        candidate.productName,
                        candidate.manufacturer,
                        candidate.modelName,
                        foundProduct
                );
                String riskLevel = RiskEvaluator.riskLevelFromScore(riskScore);

                ProductSearchResponse response = ProductSearchResponse.builder()
                        .found(true)
                        .productName(foundProduct.getProductNm())
                        .defectContent(foundProduct.getShrtcomCn())
                        .manufacturer(foundProduct.getMakr())
                        .publicationDate(foundProduct.getRecallPublictBgnde())
                        .riskScore(riskScore)
                        .riskLevel(riskLevel)
                        .build();

                // 대체 상품 추천
                List<AlternativeProductDto> alternatives =
                        alternativeProductService.findAlternatives(
                                foundProduct.getProductNm(),
                                foundProduct.getCategory(),
                                foundProduct.getMakr()
                        );
                response.setAlternatives(alternatives);

                return response;
            }
        }

        // 정확 매칭 실패 시: 정규화 기반 부분 매칭 fallback
        ProductSearchResponse fallbackResponse =
                buildFallbackResponse(normalizedProductName, normalizedManufacturer);
        if (fallbackResponse != null) {
            return fallbackResponse;
        }

        // 최종 미발견
        return ProductSearchResponse.builder()
                .found(false)
                .message("해당 제품은 리콜데이터 검색 결과에 존재하지 않습니다.")
                .build();
    }

    /* 내부 유틸 메서드 */

    // 입력값이 최소 1개라도 있는지 확인
    private boolean isValidInput(ProductSearchRequest request) {
        return StringUtils.hasText(request.getProductName()) ||
                StringUtils.hasText(request.getManufacturer()) ||
                StringUtils.hasText(request.getModelName()) ||
                (request.getImage() != null && !request.getImage().isEmpty());
    }

    // AI 분석 결과(문자열)를 파싱해서 request 필드에 세팅
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

    // 특정 입력 문자열이 비어있지 않으면 확장 실행
    private List<String> expandIfNotBlank(String value) {
        if (StringUtils.hasText(value)) {
            try {
                return searchQueryEnhancerService.enhanceQuery(value);
            } catch (Exception e) {
                log.error("검색어 확장 실패: {}", value, e);
                return List.of(value); // 실패 시 원래 값만 반환
            }
        }
        return new ArrayList<>(); // 값이 없으면 빈 리스트 반환
    }

    // 세 필드(제품명/제조사/모델명) 확장 후보를 조합해서 candidate 리스트 생성
    private List<SearchCandidate> buildSearchCandidates(List<String> productNames, List<String> manufacturers, List<String> models) {
        List<SearchCandidate> candidates = new ArrayList<>();

        if (productNames.isEmpty()) productNames = List.of("");
        if (manufacturers.isEmpty()) manufacturers = List.of("");
        if (models.isEmpty()) models = List.of("");

        for (String pn : productNames) {
            for (String mf : manufacturers) {
                for (String md : models) {
                    candidates.add(new SearchCandidate(pn, mf, md));
                }
            }
        }
        return candidates;
    }

    // DB 검색 단계 수행 (파라미터 SearchCandidate 객체로 수정, 로직도 수정함)
    private RecallProduct performSequentialSearch(SearchCandidate candidate) {
        String productName = candidate.productName;
        String manufacturer = candidate.manufacturer;
        String modelName = candidate.modelName;

        // 1단계: 제품명으로만 검색
        if (StringUtils.hasText(productName)) {
            List<RecallProduct> products = repository.findByProductNmContainingIgnoreCase(productName);
            if (!products.isEmpty()) return products.get(0);
        }

        // 2단계: 제품명 + 제조사로 검색
        if (StringUtils.hasText(productName) && StringUtils.hasText(manufacturer)) {
            List<RecallProduct> products = repository.findByProductNmContainingIgnoreCaseAndMakrContainingIgnoreCase(productName, manufacturer);
            if (!products.isEmpty()) return products.get(0);
        }

        // 3단계: 제품명 + 제조사 + 모델명으로 검색
        if (StringUtils.hasText(productName) && StringUtils.hasText(manufacturer) && StringUtils.hasText(modelName)) {
            List<RecallProduct> products = repository.findByProductNmContainingIgnoreCaseAndMakrContainingIgnoreCaseAndModlNmInfoContainingIgnoreCase(productName, manufacturer, modelName);
            if (!products.isEmpty()) return products.get(0);
        }

        // 4단계: 제조사만으로 검색
        if (!StringUtils.hasText(productName) && StringUtils.hasText(manufacturer)) {
            List<RecallProduct> products = repository.findByMakrContainingIgnoreCase(manufacturer);
            if (!products.isEmpty()) return products.get(0);
        }

        // 5단계: 모델명만으로 검색
        if (!StringUtils.hasText(productName) && !StringUtils.hasText(manufacturer) && StringUtils.hasText(modelName)) {
            List<RecallProduct> products = repository.findByModlNmInfoContainingIgnoreCase(modelName);
            if (!products.isEmpty()) return products.get(0);
        }

        return null;
    }

    // 부분 매칭 메서드
    private ProductSearchResponse buildFallbackResponse(String productName, String manufacturer) {
        if (!StringUtils.hasText(productName) && !StringUtils.hasText(manufacturer)) return null;

        List<RecallProduct> all = repository.findAll();
        for (RecallProduct p : all) {
            String dbMan = TextNormalizer.normalizeManufacturer(p.getMakr());
            String dbProd = TextNormalizer.normalizeText(p.getProductNm());

            boolean matchByProd = productName != null && dbProd != null && dbProd.contains(productName);
            boolean matchByMan = manufacturer != null && dbMan != null && dbMan.contains(manufacturer);

            if (matchByProd || matchByMan) {
                // 부분 매칭된 경우 위험 점수 계산 (필드별로 넘김)
                int riskScore = RiskEvaluator.calculateRiskScore(
                        productName,
                        manufacturer,
                        null, // fallback에서는 모델명 매칭 없음
                        p
                );
                String riskLevel = RiskEvaluator.riskLevelFromScore(riskScore);

                return ProductSearchResponse.builder()
                        .found(true)
                        .productName(p.getProductNm())
                        .defectContent(p.getShrtcomCn())
                        .manufacturer(p.getMakr())
                        .publicationDate(p.getRecallPublictBgnde())
                        .riskScore(riskScore)
                        .riskLevel(riskLevel)
                        .message("정확 매칭 실패 → 부분 매칭 결과 반환")
                        .build();
            }
        }
        return null;
    }

    private String buildDetailUrl(String recallSn) {
        if (!StringUtils.hasText(recallSn)) {
            return null;
        }

        try {
            String encodedRecallSn = URLEncoder.encode(recallSn, StandardCharsets.UTF_8);
            return DETAIL_BASE_URL + "?recallSn=" + encodedRecallSn;
        } catch (Exception e) {
            log.warn("상세 URL 생성 실패: recallSn={}", recallSn, e);
            return null;

        }
    }

    // 내부 클래스: 검색 조합 후보를 담는 단순 DTO
    private static class SearchCandidate {
        String productName;
        String manufacturer;
        String modelName;

        public SearchCandidate(String productName, String manufacturer, String modelName) {
            this.productName = productName;
            this.manufacturer = manufacturer;
            this.modelName = modelName;
        }

        @Override
        public String toString() {
            return (productName != null ? productName : "") + " " +
                    (manufacturer != null ? manufacturer : "") + " " +
                    (modelName != null ? modelName : "");
        }
    }
}
