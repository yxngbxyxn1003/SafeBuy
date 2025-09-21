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

    public String analyzeImage(MultipartFile imageFile) {
        try {
            // 이미지를 Base64로 인코딩
            String base64Image = Base64.getEncoder().encodeToString(imageFile.getBytes());
            
            // OpenAI API 요청 구성
            String url = "https://api.openai.com/v1/chat/completions";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o");
            requestBody.put("max_tokens", 1000);
            
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            
            Map<String, Object> content = new HashMap<>();
            content.put("type", "text");
            content.put("text", "이 이미지에서 제품명, 제조사, 모델명을 추출해주세요. 각각을 명확히 구분해서 출력해주세요. 형식: 제품명: [제품명], 제조사: [제조사], 모델명: [모델명]");
            
            Map<String, Object> imageContent = new HashMap<>();
            imageContent.put("type", "image_url");
            Map<String, String> imageUrl = new HashMap<>();
            imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
            imageContent.put("image_url", imageUrl);
            
            message.put("content", new Object[]{content, imageContent});
            requestBody.put("messages", new Object[]{message});
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            log.info("OpenAI API 호출 시작");
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode jsonNode = objectMapper.readTree(response.getBody());
                String analysisResult = jsonNode.get("choices").get(0).get("message").get("content").asText();
                log.info("이미지 분석 결과: {}", analysisResult);
                return analysisResult;
            } else {
                log.error("OpenAI API 호출 실패: {}", response.getStatusCode());
                return null;
            }
            
        } catch (IOException e) {
            log.error("이미지 분석 중 오류 발생", e);
            return null;
        }
    }
}
