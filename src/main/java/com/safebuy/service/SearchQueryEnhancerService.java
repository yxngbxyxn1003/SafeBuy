package com.safebuy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchQueryEnhancerService {

    private final RestTemplate restTemplate; // HTTP 요청 클라이언트
    private final ObjectMapper objectMapper; // JSON 문자열을 Java 객체로 변환하기 위한 도구

    @Value("${spring.ai.openai.api-key}")
    private String openaiApiKey;

    // OpenAI API 엔드포인트
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    // 확장에서 제거할 불필요 키워드 (한국어/영어)
    private static final Set<String> INVALID_TERMS = Set.of(
            "정보 없음", "정보없음", "없음", "모름", "N/A", "Unknown", "Not Available", "Info N/A"
    );

    /* 입력된 검색어를 gpt-4o 모델을 사용하여 다양한 변형 후보 리스트로 변환하는 메서드 */
    // 파라미터 originalQuery : 원본 검색어
    // 리턴값 : 변형된 검색어 리스트
    public List<String> enhanceQuery(String originalQuery) {
        if (com.safebuy.util.TextNormalizer.isWeakQuery(originalQuery)) {
            log.info("검색어가 너무 약하여 확장을 건너뜁니다: {}", originalQuery);
            return List.of();
        }

        // 입력값이 비어있으면 빈 리스트 반환
        if (originalQuery == null || originalQuery.isBlank()) {
            return List.of();
        }

        try {
            // HTTP 요청 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON); // JSON 형식으로 요청
            headers.setBearerAuth(openaiApiKey); // Authorization: Bearer {API_KEY}

            // 프롬프트 메시지 정의
            String prompt = String.format(
                    "다음 검색어의 가능한 변형을 JSON 배열로 출력하라. " +
                            "조건: 1) 최대 10개, 2) 중복 제거, 3) 순수 JSON 배열만 반환 (대괄호 [ ] 포함). " +
                            "절대 마크다운이나 코드블럭(```)을 포함하지 말고, " +
                            "추가 설명 없이 배열만 출력: \"%s\"",
                    originalQuery
            );

            // 요청 Body 작성
            Map<String, Object> body = new HashMap<>();
            body.put("model", "gpt-4o"); // 사용할 모델
            body.put("temperature", 0.2); // 창의성 낮게 설정 (일관된 응답을 위함)

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user"); // 대화 역할: user
            message.put("content", prompt); // 사용자 요청 프롬프트

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
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode root = objectMapper.readTree(response.getBody());

                // choices[0].message.content에 응답 텍스트가 들어있음
                String content = root.get("choices").get(0).get("message").get("content").asText();
                log.info("GPT 검색어 변형 결과(raw): {}", content);

                // 코드 블럭 제거 + JSON 배열만 추출
                content = stripCodeFenceAndExtractJsonArray(content);

                List<String> queries;
                try {
                    queries = objectMapper.readValue(content, new TypeReference<List<String>>() {});
                } catch (Exception parseEx) {
                    log.warn("응답 JSON 파싱 실패 → fallback 사용: {}", parseEx.getMessage());
                    // fallback: 원문을 하나의 요소로 감싸서 반환
                    queries = List.of(content.trim());
                }

                // 불필요 키워드 제거 + 중복 제거 + 빈 문자열 제거
                queries = queries.stream()
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .filter(s -> INVALID_TERMS.stream().noneMatch(invalid -> s.equalsIgnoreCase(invalid)))
                        .distinct()
                        .limit(10) // 안전장치: 혹시라도 10개 넘으면 자르기
                        .collect(Collectors.toList());

                return queries;
            } else {
                log.error("OpenAI API 호출 실패. 상태 코드: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("검색어 변형 중 오류 발생", e);
        }

        // 예외 발생 시 원본 검색어만 반환
        return List.of(originalQuery);
    }

    private String stripCodeFenceAndExtractJsonArray(String content) {
        if (content == null) return "[]";

        // 코드 블럭 제거
        if (content.startsWith("```")) {
            int first = content.indexOf('[');
            int last = content.lastIndexOf(']');
            if (first != -1 && last != -1 && last > first) {
                return content.substring(first, last + 1);
            }
        }

        // 배열 부분만 추출
        int first = content.indexOf('[');
        int last = content.lastIndexOf(']');
        if (first != -1 && last != -1 && last > first) {
            return content.substring(first, last + 1);
        }

        // 못 찾으면 원문 그대로 반환
        return content.trim();
    }
}
