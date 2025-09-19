package com.safebuy.service;

import com.safebuy.entity.RecallProduct;
import com.safebuy.repository.RecallProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecallService {

    private final RecallProductRepository repository;
    private final CategoryClassifierService categoryClassifierService;

    @Value("${consumer.api.service-key}")
    private String serviceKey;
    
    private final String baseUrl = "https://www.consumer.go.kr/openapi/recall/contents/index.do";

    public void updateAllData() throws Exception {
        log.info("해외리콜 전체 데이터 업데이트 시작");
        
        // SSL 인증서 검증 우회 설정 
        disableSSLVerification();
        
        int pageNo = 1;
        int cntPerPage = 100;
        int totalSavedCount = 0;

        // 1페이지 호출해서 전체 건수 확인
        String encodedServiceKey = URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);
        String url = baseUrl + "?serviceKey=" + encodedServiceKey
                + "&pageNo=" + pageNo
                + "&cntPerPage=" + cntPerPage
                + "&cntntsId=0501";

        log.info("API 호출 URL: {}", url);
        log.info("인코딩된 서비스키: {}", encodedServiceKey);
        log.info("원본 서비스키: {}", serviceKey);

        Document doc = parseXmlFromUrl(url);
        doc.getDocumentElement().normalize();

        int allCnt = Integer.parseInt(doc.getElementsByTagName("allCnt").item(0).getTextContent());
        int totalPages = (int) Math.ceil((double) allCnt / cntPerPage);

        log.info("전체 건수: {}, 전체 페이지 수: {}", allCnt, totalPages);

        for (pageNo = 1; pageNo <= totalPages; pageNo++) {
            String pageUrl = baseUrl + "?serviceKey=" + encodedServiceKey
                    + "&pageNo=" + pageNo
                    + "&cntPerPage=" + cntPerPage
                    + "&cntntsId=0501";

            log.info("페이지 {} 처리 중...", pageNo);

            // 재시도 로직 (최대 3회)
            boolean success = false;
            int retryCount = 0;
            Document pageDoc = null;
            
            while (!success && retryCount < 3) {
                try {
                    pageDoc = parseXmlFromUrl(pageUrl);
                    pageDoc.getDocumentElement().normalize();
                    success = true;
                } catch (Exception e) {
                    retryCount++;
                    log.warn("페이지 {} 재시도 {}/3: {}", pageNo, retryCount, e.getMessage());
                    if (retryCount < 3) {
                        Thread.sleep(1000 * retryCount); // 재시도 간격 점진적 증가
                    }
                }
            }
            
            if (!success) {
                log.error("페이지 {} 최대 재시도 횟수 초과, 건너뜀", pageNo);
                continue;
            }
            
            try {

                NodeList nList = pageDoc.getElementsByTagName("content");

                List<RecallProduct> products = new ArrayList<>();

                for (int i = 0; i < nList.getLength(); i++) {
                    Node node = nList.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element e = (Element) node;

                        String productNm = getTagValue("productNm", e);
                        String bsnmNm = getTagValue("bsnmNm", e);
                        String makr = getTagValue("makr", e);
                        String modlNmInfo = getTagValue("modlNmInfo", e);
                        String recallPublictBgnde = getTagValue("recallPublictBgnde", e);
                        String shrtcomCn = getTagValue("shrtcomCn", e);

                        // 필수 필드 4개가 모두 존재할 때만 저장 (모델명, 결함내용, 공표시작일, 제조사)
                        if (isNotBlank(makr) && isNotBlank(modlNmInfo) && isNotBlank(recallPublictBgnde) && isNotBlank(shrtcomCn)) {

                            RecallProduct product = new RecallProduct();
                            product.setRecallSn(getTagValue("recallSn", e));
                            product.setProductNm(productNm);
                            product.setBsnmNm(bsnmNm);
                            product.setMakr(makr);
                            product.setModlNmInfo(modlNmInfo);
                            product.setRecallPublictBgnde(recallPublictBgnde);
                            product.setShrtcomCn(shrtcomCn);
                            
                            // 제품명을 기반으로 카테고리 분류
                            String category = categoryClassifierService.classifyProduct(productNm);
                            product.setCategory(category);

                            products.add(product);
                        }
                    }
                }

                if (!products.isEmpty()) {
                    repository.saveAll(products);
                    totalSavedCount += products.size();
                    log.info("페이지 {}/{} 저장 완료 (저장된 행 수: {}, 누적 저장 수: {})", 
                            pageNo, totalPages, products.size(), totalSavedCount);
                } else {
                    log.info("페이지 {}/{} 저장할 데이터 없음", pageNo, totalPages);
                }

            } catch (Exception e) {
                log.error("페이지 {} 처리 중 오류 발생: {}", pageNo, e.getMessage());
                log.info("페이지 {} 건너뛰고 다음 페이지 계속 처리...", pageNo);
            }

            // API 호출 간격 조절 (서버 부하 방지) - 간격을 늘림
            Thread.sleep(500);
        }

        log.info("해외리콜 전체 데이터 업데이트 완료! 총 저장된 데이터 수: {}", totalSavedCount);
    }

    private String getTagValue(String tag, Element element) {
        NodeList nodeList = element.getElementsByTagName(tag);
        if (nodeList.getLength() == 0) return null;
        Node node = nodeList.item(0).getFirstChild();
        return node != null ? node.getNodeValue().trim() : null;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    //XML 파싱
    private Document parseXmlFromUrl(String urlString) throws Exception {
        log.info("API 호출 시작: {}", urlString);
        
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        connection.setRequestMethod("GET");
        
        // 테스트 API에서 성공한 헤더 설정 적용
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8,application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
        connection.setRequestProperty("Connection", "keep-alive");
        connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        
        log.info("설정된 헤더:");
        log.info("  Accept: {}", connection.getRequestProperty("Accept"));
        log.info("  User-Agent: {}", connection.getRequestProperty("User-Agent"));
        log.info("  Accept-Language: {}", connection.getRequestProperty("Accept-Language"));
        log.info("  Accept-Encoding: {}", connection.getRequestProperty("Accept-Encoding"));
        log.info("  Connection: {}", connection.getRequestProperty("Connection"));
        log.info("  Upgrade-Insecure-Requests: {}", connection.getRequestProperty("Upgrade-Insecure-Requests"));
        
        int responseCode = connection.getResponseCode();
        log.info("응답 코드: {}", responseCode);
        
        InputStream rawStream;
        if (responseCode != 200) {
            // 오류 스트림 우선적으로 읽어 상세 로그 출력
            InputStream err = connection.getErrorStream();
            String errBody = err != null ? new String(err.readAllBytes(), StandardCharsets.UTF_8) : "";
            log.error("오류 응답 본문: {}", errBody);
            throw new Exception("HTTP 오류: " + responseCode + (errBody.isEmpty() ? "" : " - " + errBody));
        } else {
            rawStream = connection.getInputStream();
        }
        
        // Content-Encoding 검사 후 gzip 처리
        String contentEncoding = connection.getHeaderField("Content-Encoding");
        InputStream inputStream;
        if (contentEncoding != null && contentEncoding.contains("gzip")) {
            inputStream = new java.util.zip.GZIPInputStream(rawStream);
        } else {
            inputStream = rawStream;
        }
        
        String xmlContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        inputStream.close();
        connection.disconnect();
        
        log.info("XML 응답 받음, 길이: {}", xmlContent.length());
        log.debug("XML 내용 (처음 500자): {}", xmlContent.substring(0, Math.min(500, xmlContent.length())));
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
        
        return doc;
    }

    //SSL 인증서 검증을 우회하는 설정
    private void disableSSLVerification() {
        try {
            // 모든 인증서를 신뢰하는 TrustManager 생성
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        // 모든 클라이언트 인증서 신뢰
                    }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        // 모든 서버 인증서 신뢰
                    }
                }
            };

            // SSL 컨텍스트 초기화 (TLS 사용)
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // 호스트명 검증 비활성화
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            // 시스템 속성 설정으로 추가 보안 검증 우회
            System.setProperty("com.sun.net.ssl.checkRevocation", "false");
            System.setProperty("sun.security.ssl.allowUnsafeRenegotiation", "true");
            
            log.info("SSL 인증서 검증 우회 설정 완료");
        } catch (Exception e) {
            log.error("SSL 설정 중 오류 발생", e);
            throw new RuntimeException("SSL 설정 실패", e);
        }
    }
}
