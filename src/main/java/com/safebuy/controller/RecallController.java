package com.safebuy.controller;

import com.safebuy.dto.ProductSearchRequest;
import com.safebuy.dto.ProductSearchResponse;
import com.safebuy.service.ProductSearchService;
import com.safebuy.service.RecallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/* 상태 코드 정책 */
// 200 OK: 리콜 DB에서 매칭된 제품이 존재 (riskScore >= 1, riskLevel != "없음")
// 404 NOT_FOUND: 유효한 요청이었지만 매칭 결과가 없음 (riskScore == 0, riskLevel == "없음")
// 400 BAD_REQUEST: 입력값 자체가 부적절(필수 최소 입력 미충족 등)
// 500 SERVER_ERROR: 서버 내부 오류

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
            // SSL 우회 설정
            disableSSLVerification();

            // 간단한 테스트 API 호출
            String testUrl = "https://www.consumer.go.kr/openapi/recall/contents/index.do?serviceKey=S54NVI2HQL&pageNo=1&cntPerPage=1&cntntsId=0501";

            java.net.URL url = new java.net.URL(testUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();

            // 브라우저와 동일한 헤더 설정
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8,application/json");
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

    //SSL 인증서 검증을 우회하는 설정
    private void disableSSLVerification() {
        try {
            // 모든 인증서를 신뢰하는 TrustManager 생성
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }

                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                            // 모든 클라이언트 인증서 신뢰
                        }

                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                            // 모든 서버 인증서 신뢰
                        }
                    }
            };

            // SSL 컨텍스트 초기화 (TLS 사용)
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // 호스트명 검증 비활성화
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });

            // 시스템 속성 설정으로 추가 보안 검증 우회
            System.setProperty("com.sun.net.ssl.checkRevocation", "false");
            System.setProperty("sun.security.ssl.allowUnsafeRenegotiation", "true");

        } catch (Exception e) {
            // 로그 없이 조용히 처리
        }
    }

    // 제품 검색 엔드포인트
    // - 폼데이터 업로드(이미지 포함)를 고려하여 consumes에 MULTIPART를 명시
    //   -> (이미지가 없고 텍스트만 보낼 때도 프론트에서 multipart/form-data로 보내면 이 핸들러가 처리)
    // - 모든 파라미터는 선택(optional)로 받음
    @PostMapping(
            value = "/search",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE}
    )
    public ResponseEntity<ProductSearchResponse> searchProduct(
            @RequestParam(value = "productName", required = false) String productName,
            @RequestParam(value = "manufacturer", required = false) String manufacturer,
            @RequestParam(value = "modelName", required = false) String modelName,
            @RequestParam(value = "image", required = false) MultipartFile image) {

        try {
            // 모바일 디버깅을 위한 상세 로깅
            log.info("제품 검색 요청 수신 - 제품명: {}, 제조사: {}, 모델명: {}, 이미지: {}",
                    productName, manufacturer, modelName, image != null ? "있음" : "없음");

            // 이미지 파일 상세 정보 로깅 (모바일 문제 진단용)
            if (image != null) {
                log.info("이미지 파일 상세 정보 - 원본명: {}, 크기: {}, 타입: {}, 비어있음: {}", 
                        image.getOriginalFilename(), 
                        image.getSize(), 
                        image.getContentType(),
                        image.isEmpty());
                
                // 모바일에서 자주 발생하는 문제들 테스트
                if (image.isEmpty()) {
                    log.warn("모바일 업로드 문제: 이미지 파일이 비어있음");
                }
                if (image.getOriginalFilename() == null || image.getOriginalFilename().trim().isEmpty()) {
                    log.warn("모바일 업로드 문제: 파일명이 없음");
                }
                if (image.getContentType() == null) {
                    log.warn("모바일 업로드 문제: Content-Type이 없음");
                }
            } else {
                log.warn("모바일 업로드 문제: 이미지 파일이 null로 전달됨");
            }

            // 요청 DTO 구성
            ProductSearchRequest request = new ProductSearchRequest();
            request.setProductName(productName);
            request.setManufacturer(manufacturer);
            request.setModelName(modelName);
            request.setImage(image);

            // 서비스 호출: 내부에서 이미지 파싱/검색어 확장/DB 조회/위험도 산출까지 완료됨
            ProductSearchResponse response = productSearchService.searchProduct(request);

            /* 상태코드 매핑 */
            // 입력값 부재 등 잘못된 요청 -> 400 ("최소 하나 이상의 정보를 입력해주세요." 메시지로 알려줌)
            if (!response.isFound()
                    && "최소 하나 이상의 정보를 입력해주세요.".equals(response.getMessage())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // 검색 결과 없음 -> 404 (위험 단계 "없음"에 해당)
            if (!response.isFound()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // 검색 성공 -> 200
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("제품 검색 중 서버 오류 발생", e);

            // 서버 내부 오류 -> 500
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ProductSearchResponse.builder()
                            .found(false)
                            .message("서버 오류가 발생했습니다: " + e.getMessage())
                            .build());
        }
    }
}
