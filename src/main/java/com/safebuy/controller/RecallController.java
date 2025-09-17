package com.safebuy.controller;

import com.safebuy.dto.ProductSearchRequest;
import com.safebuy.dto.ProductSearchResponse;
import com.safebuy.service.ProductSearchService;
import com.safebuy.service.RecallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/recalls")
@Slf4j
public class RecallController {

    private final RecallService recallService;
    private final ProductSearchService productSearchService;

    @PostMapping("/updateAll")
    public ResponseEntity<String> updateAll() {
        try {
            log.info("해외리콜 전체 데이터 업데이트 요청 받음");
            recallService.updateAllData();
            return ResponseEntity.ok("해외 리콜 전체 데이터 업데이트 완료!");
        } catch (Exception e) {
            log.error("해외리콜 데이터 업데이트 실패", e);
            return ResponseEntity.internalServerError()
                    .body("업데이트 실패: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    public ResponseEntity<String> getStatus() {
        return ResponseEntity.ok("해외리콜 API 서비스가 정상적으로 실행 중입니다.");
    }

    @GetMapping("/test")
    public ResponseEntity<String> testApi() {
        try {
            // 간단한 테스트 API 호출
            String testUrl = "https://www.consumer.go.kr/openapi/recall/contents/index.do?serviceKey=S54NVI2HQL&pageNo=1&cntPerPage=1&cntntsId=0501";
            
            java.net.URL url = new java.net.URL(testUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            
            // 브라우저와 동일한 헤더 설정
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            
            int responseCode = connection.getResponseCode();
            String response = "응답 코드: " + responseCode;
            
            if (responseCode == 200) {
                java.io.InputStream inputStream = connection.getInputStream();
                String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                inputStream.close();
                response += "\n응답 길이: " + content.length();
                response += "\n응답 내용 (처음 200자): " + content.substring(0, Math.min(200, content.length()));
            } else {
                response += "\n오류 메시지: " + connection.getResponseMessage();
                try {
                    java.io.InputStream errorStream = connection.getErrorStream();
                    if (errorStream != null) {
                        String errorContent = new String(errorStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        response += "\n오류 내용: " + errorContent;
                    }
                } catch (Exception e) {
                    response += "\n오류 스트림 읽기 실패: " + e.getMessage();
                }
            }
            
            connection.disconnect();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok("테스트 실패: " + e.getMessage());
        }
    }

    @PostMapping("/search")
    public ResponseEntity<ProductSearchResponse> searchProduct(
            @RequestParam(value = "productName", required = false) String productName,
            @RequestParam(value = "manufacturer", required = false) String manufacturer,
            @RequestParam(value = "modelName", required = false) String modelName,
            @RequestParam(value = "image", required = false) org.springframework.web.multipart.MultipartFile image) {
        
        try {
            log.info("제품 검색 요청 - 제품명: {}, 제조사: {}, 모델명: {}, 이미지: {}", 
                    productName, manufacturer, modelName, image != null ? "있음" : "없음");
            
            ProductSearchRequest request = new ProductSearchRequest();
            request.setProductName(productName);
            request.setManufacturer(manufacturer);
            request.setModelName(modelName);
            request.setImage(image);
            
            ProductSearchResponse response = productSearchService.searchProduct(request);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("제품 검색 중 오류 발생", e);
            return ResponseEntity.internalServerError()
                    .body(ProductSearchResponse.builder()
                            .found(false)
                            .message("검색 중 오류가 발생했습니다: " + e.getMessage())
                            .build());
        }
    }
}
