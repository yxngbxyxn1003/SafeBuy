package com.safebuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

// 이미지(멀티파트)를 LLM에 전달해 제품명/제조사/모델명을 추출하는 서비스 클래스
// 여기서는 문자열(String) 그대로 반환하고, 상위 서비스가 JSON 파싱/폴백 처리함
@Service
@Slf4j
public class ImageAnalysisService {

    @Value("${spring.ai.openai.api-key}")
    private String openaiApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ImageAnalysisService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // 파라미터 imageFile: 업로드 된 MultipartFile 이미지 파일
    // 반환값: LLM의 응답 원문 (실패 혹은 에러 시 null 반환)
    public String analyzeImage(MultipartFile imageFile) {
        try {
            // 이미지를 Base64로 인코딩
            String base64Image = Base64.getEncoder().encodeToString(imageFile.getBytes());
            
            // OpenAI API 요청 구성
            String url = "https://api.openai.com/v1/chat/completions";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            // 메시지 Payload 구성
            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "text");
            textPart.put("text",
                    // 출력 형식을 'JSON만' 강제. 코드블럭/주석/설명 금지.
                    "다음 형식의 '순수 JSON 객체'만 출력하세요. 코드블럭, 설명, 주석, 추가 텍스트 금지.\n" +
                            "{\"productName\":\"\",\"manufacturer\":\"\",\"modelName\":\"\"}\n" +
                            "- 가능하면 실제 제품명/제조사/모델명을 채우고, 불명확하면 빈 문자열 유지.\n" +
                            "- 범용 카테고리어만 보이면 productName에 그 텍스트를 넣고, manufacturer/modelName은 빈 문자열로 두세요."
                    );
            
            Map<String, Object> imagePart = new HashMap<>();
            imagePart.put("type", "image_url");
            Map<String, String> imageUrl = new HashMap<>();
            imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
            imagePart.put("image_url", imageUrl);

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            // content는 배열로 텍스트/이미지 파트를 섞어서 전달
            message.put("content", new Object[]{textPart, imagePart});

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o");
            requestBody.put("temperature", 0.1); // 일관성 향상
            requestBody.put("max_tokens", 300);
            requestBody.put("messages", new Object[]{ message });
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            log.info("OpenAI API 호출 시작(이미지 분석)");
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                log.error("OpenAI API 호출 실패: status={}", response.getStatusCode());
                return null;
            }

            // choices[0].message.content 에 LLM 응답 텍스트가 들어옴
            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            log.info("이미지 분석 결과(raw): {}", content);

            return content; // ↑ JSON 문자열이길 기대. 실제 파싱은 상위 서비스에서 처리.
            
        } catch (IOException e) {
            log.error("이미지 분석 중 오류 발생", e);
            return null;
        }
    }
}
