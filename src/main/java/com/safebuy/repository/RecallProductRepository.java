package com.safebuy.repository;

import com.safebuy.entity.RecallProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecallProductRepository extends JpaRepository<RecallProduct, String> {
    
    // 제품명으로 검색
    List<RecallProduct> findByProductNmContainingIgnoreCase(String productName);
    
    // 제조사로 검색
    List<RecallProduct> findByMakrContainingIgnoreCase(String manufacturer);
    
    // 모델명으로 검색
    List<RecallProduct> findByModlNmInfoContainingIgnoreCase(String modelName);
    
    // 제품명 + 제조사로 검색
    List<RecallProduct> findByProductNmContainingIgnoreCaseAndMakrContainingIgnoreCase(
            String productName, String manufacturer);
    
    // 제품명 + 제조사 + 모델명으로 검색
    List<RecallProduct> findByProductNmContainingIgnoreCaseAndMakrContainingIgnoreCaseAndModlNmInfoContainingIgnoreCase(
            String productName, String manufacturer, String modelName);
}
