package com.safebuy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safebuy.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.w3c.dom.Text;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchQueryEnhancerService {

    private final RestTemplate restTemplate; // HTTP 요청 클라이언트
    private final ObjectMapper objectMapper; // JSON 문자열을 Java 객체로 변환하기 위한 도구
    private final RecallDictionaryService recallDictionaryService; // 딕셔너리 기반 후보 필터링

    @Value("${spring.ai.openai.api-key}")
    private String openaiApiKey;

    // OpenAI API 엔드포인트
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    // 확장에서 제거할 무의미한 키워드
    private static final Set<String> INVALID_TERMS = Set.of(
            "정보 없음", "정보없음", "없음", "모름", "n/a", "na", "unknown", "not available", "info n/a", "n\\a"
    );


    /* 메모리 캐시 시스템에서 하나의 저장 항목(Entry)을 정의하는 구조체: 입력 질의 -> 확장 결과 리스트, TTL 10분 */
    private static final class CacheEntry {
        final List<String> value; // 캐시 데이터
        final long expiresAtMillis; // 만료 시간

        CacheEntry(List<String> value, long expiresAtMillis) {
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>(); // 캐시 저장공간
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int CACHE_MAX_SIZE = 5_000;


    /* 입력된 검색어를 gpt-4o 모델을 사용하여 다양한 변형 후보 리스트로 변환하는 메서드 */
    // 파라미터 originalQuery: 원본 검색어
    // 파라미터 field: RecallDictionaryService의 필드
    // 리턴값 : 변형된 검색어 리스트
    public List<String> enhanceQuery(String originalQuery, RecallDictionaryService.Field field) {
        // 노이즈 강한 질의 방어: 한 글자 혹은 초성은 확장 자체를 생략함
        if (TextNormalizer.isWeakQuery(originalQuery)) {
            log.info("[Enhancer] 노이즈가 강한 질의이므로 확장 생략: {}", originalQuery);
            return List.of();
        }
        // 입력값이 비어있으면 빈 리스트 반환
        if (originalQuery == null || originalQuery.isBlank()) {
            return List.of();
        }

        // 캐시 키: (field or ANY) + '|' 정규화된 원문
        final String normalizedKey = TextNormalizer.normalizeText(originalQuery);
        final String fieldKey = (field == null) ? "ANY" : field.name();
        final String cacheKey = fieldKey + "|" + normalizedKey;

        // 캐시 조회
        List<String> cached = getFromCache(cacheKey);
        if (cached != null) {
            log.debug("[Enhancer] 캐시 hit: key={} (size={})", cacheKey, cached.size());
            return cached;
        }

        try {
            // 1. OpenAI 호출
            // HTTP 요청 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON); // JSON 형식으로 요청
            headers.setBearerAuth(openaiApiKey);

            // 프롬프트 메시지 정의
            String prompt = buildPrompt(originalQuery, field);

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user"); // 대화 역할: user
            message.put("content", prompt); // 사용자 요청 프롬프트

            Map<String, Object> body = new HashMap<>();
            body.put("model", "gpt-4o"); // 사용할 모델
            body.put("temperature", 0.2); // 창의성 낮게 설정 (일관된 응답을 위함)
            body.put("messages", List.of(message));

            // RestTemplate으로 POST 요청 준비
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            // API 호출
            ResponseEntity<String> response = restTemplate.exchange(
                    OPENAI_API_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            // 응답 처리
            if (response.getStatusCode() != HttpStatus.OK) {
                log.error("[Enhancer] OpenAI 호출 실패: status={}", response.getStatusCode());
                return List.of(originalQuery); // 실패 시 원본만 반환(안전)
            }

            // 2. 응답 파싱 안정화: 코드펜스 혹은 여분 텍스트 섞여도 JSON 배열만 추출
            JsonNode root = objectMapper.readTree(response.getBody());
            String raw = root.path("choices").path(0).path("message").path("content").asText();
            log.info("GPT 검색어 변형 결과(raw): {}", raw);

            String jsonArrayText = stripCodeFenceAndExtractJsonArray(raw); // 배열만 추출
            List<String> llmCandidates = safeParseJsonArray(jsonArrayText); // 실패 시 단일 요소 fallback

            // 3. 후보 정제: 금지어/공백/중복 제거, 노이즈 강한 질의 제거
            List<String> cleaned = cleanAndDedup(llmCandidates);

            // 4. 사전 필터: 필드 지정 시 "존재 가능" 후보만 남김
            List<String> finalList;
            if (field != null) {
                finalList = recallDictionaryService.filterCandidates(cleaned, field, 10);
            } else {
                // 필드 미지정 시 사전 필터 생략, 정제된 상위 10개만 추출
                finalList = cleaned.stream().limit(10).collect(Collectors.toList());
            }

            // 캐시에 저장
            putToCache(cacheKey, finalList);
            return finalList;
        } catch (Exception e) {
            log.error("[Enhancer] 검색어 변형 중 오류", e);
            return List.of(originalQuery);
        }
    }

    // 내부 메서드

    /* 필드별 힌트를 프롬프트에 반영하는 메서드 */
    // 제조사: Inc/Ltd/주식회사/㈜ 등 회사형태 제거된 "순수 명칭" 위주 변형
    // 제품/모델: 공백/하이픈/대소문자/한영 표기 변형 위주
    // 공통: 쇼핑/일반 키워드 금지, JSON 배열만, 최대 10개, 중복 제거
    private String buildPrompt(String originalQuery, RecallDictionaryService.Field field) {
        String fieldHint;
        if (field == RecallDictionaryService.Field.MANUFACTURER) {
            fieldHint = "입력을 '제조사명'으로 간주하라. Inc, Ltd, 주식회사, ㈜ 등 회사형태 표기는 제거하고, " +
                    "띄어쓰기/대소문자/복수형/하이픈/약어 변형만 허용하라. ";
        } else if (field == RecallDictionaryService.Field.MODEL) {
            fieldHint = "입력을 '모델명'으로 간주하라. 공백/하이픈/대소문자 표기 변형만 허용하라. ";
        } else if (field == RecallDictionaryService.Field.PRODUCT) {
            fieldHint = "입력을 '제품명'으로 간주하라. 공백/하이픈/대소문자/한영 표기 변형만 허용하라. ";
        } else {
            fieldHint = "입력을 DB에 존재할 수 있는 항목으로 간주하고, 공백/하이픈/대소문자/한영 표기 변형만 허용하라. ";
        }

        return String.format(
                "%s" +
                        "다음 검색어를 DB에 실제로 존재할 법한 형태로만 변형하여 '순수 JSON 배열'로 출력하라. " +
                        "조건: 1) 최대 10개, 2) 중복 제거, 3) 배열만 반환([ ] 포함). " +
                        "다음 키워드는 절대 포함하지 말 것: 추천, 가격, 후기, 사용법, 효과, 종류, 브랜드, 구매, 안전, 매뉴얼, 리뷰, 할인, 쿠폰, 대여, 수리. " +
                        "코드블럭(```)이나 추가 설명 없이 배열만 출력: \"%s\"",
                fieldHint, originalQuery
        );
    }

    /* 원문에 코드펜스 혹은 텍스트가 섞여 있어도 JSON 배열 부분만 추출하는 메서드 */
    private String stripCodeFenceAndExtractJsonArray(String content) {
        if (content == null) return "[]";

        // 코드 블럭 시작이면 블럭 안의 배열만 추출
        if (content.startsWith("```")) {
            int first = content.indexOf('[');
            int last = content.lastIndexOf(']');
            if (first != -1 && last != -1 && last > first) {
                return content.substring(first, last + 1);
            }
        }

        // 일반 텍스트에 배열이 섞인 경우도 처리
        int first = content.indexOf('[');
        int last = content.lastIndexOf(']');
        if (first != -1 && last != -1 && last > first) {
            return content.substring(first, last + 1);
        }

        // 못 찾으면 원문 그대로 반환 (safeParseJsonArray 단계에서 대비)
        return content.trim();
    }

    /* JSON 배열 파싱을 시도하고, 실패하면 단일 요소 배열로 폴백하는 메서드(LLM이 가끔 따옴표 누락/콤마 오류를 낼 때를 대비) */
    // 추출된 텍스트를 실제 List<String>으로 파싱
    private List<String> safeParseJsonArray(String jsonArrayText) {
        try {
            return objectMapper.readValue(jsonArrayText, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.warn("[Enhancer] JSON 파싱 실패 → 단일 요소 fallback: {}", ex.getMessage());
            String trimmed = (jsonArrayText == null) ? "" : jsonArrayText.trim();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed);
        }
    }

    /* 금지어/공백/노이즈가 강한 질의 제거 + 중복 제거 + 상한(10) 적용 */
    private List<String> cleanAndDedup(List<String> src) {
        if (src == null || src.isEmpty()) return List.of();

        return src.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(TextNormalizer::normalizeText) // 비교적 일관된 비교를 위해 1차 정규화
                .filter(s -> !TextNormalizer.isWeakQuery(s)) // 한글 초성/한 글자 등 노이즈 강한 질의 제거
                .filter(s -> INVALID_TERMS.stream().noneMatch(inv -> s.equalsIgnoreCase(inv)))
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
    }

    /* 메모리 캐시 유틸 메서드 */
    private List<String> getFromCache(String key) {
        CacheEntry e = cache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expiresAtMillis) {
            cache.remove(key);
            return null;
        }
        return e.value;
    }

    private void putToCache(String key, List<String> value) {
        if (value == null) return;
        // 캐시 크기 상한 간단 방어
        if (cache.size() > CACHE_MAX_SIZE) {
            // 가장 단순한 방어: 전부 비우기 (필요 시 LRU로 개선 가능)
            cache.clear();
        }
        long exp = System.currentTimeMillis() + CACHE_TTL.toMillis();
        cache.put(key, new CacheEntry(value, exp));
    }
}
