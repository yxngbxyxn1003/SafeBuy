package com.safebuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        // 정규화 단계
        String normalizedProductName = TextNormalizer.normalizeText(request.getProductName());
        String normalizedManufacturer = TextNormalizer.normalizeManufacturer(request.getManufacturer());
        String normalizedModelName = TextNormalizer.normalizeText(request.getModelName());

        // 추가: 정규화된 값이 약한 검색어면 null 처리
        if (TextNormalizer.isWeakQuery(normalizedProductName)) normalizedProductName = null;
        if (TextNormalizer.isWeakQuery(normalizedManufacturer)) normalizedManufacturer = null;
        if (TextNormalizer.isWeakQuery(normalizedModelName)) normalizedModelName = null;

        // 추가: 정규화 이후에도 세 필드가 모두 null이면 의미 있는 검색어가 없는 것
        if (normalizedProductName == null && normalizedManufacturer == null && normalizedModelName == null
                && (request.getImage() == null || request.getImage().isEmpty())) {
            return ProductSearchResponse.builder()
                    .found(false)
                    .message("입력값이 너무 짧거나 의미가 없습니다. 최소 두 글자 이상의 제품명/모델명 또는 제조사를 입력해 주세요.")
                    .build();
        }

        // 검색어 확장 (정규화 된 문자열 기반)
        List<String> expandedProductNames = expandIfNotBlank(normalizedProductName, RecallDictionaryService.Field.PRODUCT);
        List<String> expandedManufacturers = expandIfNotBlank(normalizedManufacturer, RecallDictionaryService.Field.MANUFACTURER);
        List<String> expandedModels = expandIfNotBlank(normalizedModelName, RecallDictionaryService.Field.MODEL);

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
                        .detailUrl(buildDetailUrl(foundProduct.getRecallSn()))
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
        boolean hasTextInput = StringUtils.hasText(request.getProductName()) ||
                              StringUtils.hasText(request.getManufacturer()) ||
                              StringUtils.hasText(request.getModelName());
        boolean hasValidImage = isValidImage(request.getImage());
        
        log.info("입력값 검증 - 텍스트 입력: {}, 유효한 이미지: {}", hasTextInput, hasValidImage);
        
        return hasTextInput || hasValidImage;
    }
    
    // 이미지 파일 검증 (모바일 호환성 개선)
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

    // AI 분석 결과(문자열)를 파싱해서 request에 제품명/제조사/모델명을 채워 넣는다.
    // 단계:
    // 1. JSON 파싱 시도 → productName/manufacturer/modelName 키가 있으면 그대로 세팅
    // 2. JSON이 아니면 "제품명:", "제조사:", "모델명:" 패턴을 정규식으로 검색
    // 3. 그래도 없으면 긴 문자열이나 따옴표 문자열 등을 fallback으로 사용
    private void parseAnalysisResult(String analysisResult, ProductSearchRequest request) {
        if (!StringUtils.hasText(analysisResult)) {
            log.warn("이미지 분석 결과가 비어있어 파싱을 건너뜁니다.");
            return;
        }

        // 1. JSON 파싱 시도
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(analysisResult);

            // JSON 구조라면 키값 추출
            String productName = root.path("productName").asText(null);
            String manufacturer = root.path("manufacturer").asText(null);
            String modelName = root.path("modelName").asText(null);

            if (StringUtils.hasText(productName) && !StringUtils.hasText(request.getProductName())) {
                request.setProductName(productName.trim());
            }
            if (StringUtils.hasText(manufacturer) && !StringUtils.hasText(request.getManufacturer())) {
                request.setManufacturer(manufacturer.trim());
            }
            if (StringUtils.hasText(modelName) && !StringUtils.hasText(request.getModelName())) {
                request.setModelName(modelName.trim());
            }

            // JSON에서 최소 하나라도 세팅됐다면 종료
            if (StringUtils.hasText(productName) || StringUtils.hasText(manufacturer) || StringUtils.hasText(modelName)) {
                log.info("이미지 분석 결과 JSON 파싱 성공 → request에 값 세팅 완료");
                return;
            }
        } catch (Exception e) {
            // JSON 파싱 실패는 정상적인 fallback 흐름
            log.debug("이미지 분석 결과 JSON 파싱 실패, 정규식 기반 파싱으로 진행: {}", e.getMessage());
        }

        // 2. 정규식 패턴 매칭
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

        // 3. fallback - 긴 단어/따옴표 문자열 추출
        if (!StringUtils.hasText(request.getProductName())) {
            // 따옴표 안의 텍스트 추출
            Matcher quoted = Pattern.compile("\"([^\"]{2,})\"").matcher(analysisResult);
            if (quoted.find()) {
                request.setProductName(quoted.group(1).trim());
                log.info("Fallback 적용(따옴표 텍스트) → productName={}", quoted.group(1).trim());
            } else {
                // 공백 기준으로 나눈 단어 중 길이가 긴 것 사용
                String[] tokens = analysisResult.split("\\s+");
                String candidate = null;
                for (String token : tokens) {
                    if (token.length() >= 3) {
                        candidate = token;
                        break;
                    }
                }
                if (candidate != null) {
                    request.setProductName(candidate.trim());
                    log.info("Fallback 적용(긴 토큰) → productName={}", candidate.trim());
                }
            }
        }
    }

    // 특정 입력 문자열이 비어있지 않으면 확장 실행
    private List<String> expandIfNotBlank(String value, RecallDictionaryService.Field field) {
        // value는 이미 정규화된 값이 들어옴
        if (StringUtils.hasText(value) && !TextNormalizer.isWeakQuery(value)) {
            try {
                return searchQueryEnhancerService.enhanceQuery(value, field);
            } catch (Exception e) {
                log.error("검색어 확장 실패: {}", value, e);
                return List.of(value);
            }
        }
        return new ArrayList<>(); // 노이즈가 강하거나 비어있으면 빈 리스트 → 해당 필드 미사용
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
        String productName = TextNormalizer.isWeakQuery(candidate.productName) ? null : candidate.productName;
        String manufacturer = TextNormalizer.isWeakQuery(candidate.manufacturer) ? null : candidate.manufacturer;
        String modelName = TextNormalizer.isWeakQuery(candidate.modelName) ? null : candidate.modelName;

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
        if (TextNormalizer.isWeakQuery(productName)) productName = null;
        if (TextNormalizer.isWeakQuery(manufacturer)) manufacturer = null;
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
                        .detailUrl(buildDetailUrl(p.getRecallSn()))
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
