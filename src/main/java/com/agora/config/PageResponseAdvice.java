package com.agora.config;

import com.agora.dto.common.BaseSearchParam;
import com.agora.dto.common.PageResponse;
import com.agora.dto.common.PageResponseFactory;
import com.agora.util.PageConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 全局分頁響應處理器
 * 自動將所有返回 Spring Data Page 的響應轉換為 PageResponse（1-based pageNumber）
 * 
 * 這樣就不需要在每個控制器中手動轉換了
 */
@Slf4j
@ControllerAdvice(basePackages = "com.agora.controller")
public class PageResponseAdvice implements ResponseBodyAdvice<Object> {
    
    /**
     * 驗證並調整搜索參數
     * 包括：設置默認值、驗證範圍、將 1-based page 轉換為 0-based（用於 Spring Data）
     * 
     * @param searchParam 搜索參數（1-based page）
     * @return 調整後的搜索參數（0-based page，可直接用於 Spring Data）
     */
    public static BaseSearchParam validateAndAdjust(BaseSearchParam searchParam) {
        if (searchParam == null) {
            throw new IllegalArgumentException("搜索參數不能為空");
        }
        
        // 記錄原始值（用於日誌）
        int originalPage = searchParam.getPage();
        
        // 設置默認值並驗證範圍
        if (searchParam.getPage() < 1) {
            searchParam.setPage(1);
        }
        
        if (searchParam.getSize() < 1) {
            searchParam.setSize(20);
        } else if (searchParam.getSize() > 100) {
            searchParam.setSize(100);
        }
        
        if (searchParam.getSortDirection() == null) {
            searchParam.setSortDirection("DESC");
        }
        
        // 將 1-based page 轉換為 0-based（用於 Spring Data）
        searchParam.setPage(PageConverter.toZeroBased(searchParam.getPage()));
        
        log.debug("搜索參數調整: page {} -> {} (0-based), size={}", 
                originalPage, searchParam.getPage(), searchParam.getSize());
        
        return searchParam;
    }

    @Override
    public boolean supports(@NonNull MethodParameter returnType, @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        // 檢查返回類型是否為 Page 或 ResponseEntity<Page>
        Class<?> returnTypeClass = returnType.getParameterType();
        
        // 直接返回 Page 的情況
        if (org.springframework.data.domain.Page.class.isAssignableFrom(returnTypeClass)) {
            return true;
        }
        
        // ResponseEntity<Page> 的情況
        if (org.springframework.http.ResponseEntity.class.isAssignableFrom(returnTypeClass)) {
            // 檢查泛型參數是否為 Page
            Class<?> nestedType = returnType.getNestedParameterType();
            return org.springframework.data.domain.Page.class.isAssignableFrom(nestedType);
        }
        
        return false;
    }

    @Override
    @Nullable
    public Object beforeBodyWrite(@Nullable Object body, @NonNull MethodParameter returnType, @NonNull MediaType selectedContentType,
                                   @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                   @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
        
        // 處理 ResponseEntity<Page> 的情況
        if (body instanceof org.springframework.http.ResponseEntity) {
            org.springframework.http.ResponseEntity<?> responseEntity = (org.springframework.http.ResponseEntity<?>) body;
            Object responseBody = responseEntity.getBody();
            
            if (responseBody instanceof org.springframework.data.domain.Page) {
                org.springframework.data.domain.Page<?> page = (org.springframework.data.domain.Page<?>) responseBody;
                PageResponse<?> pageResponse = convertPageToPageResponse(page);
                
                // 返回新的 ResponseEntity，保持原有的狀態碼和頭部
                return org.springframework.http.ResponseEntity
                    .status(responseEntity.getStatusCode())
                    .headers(responseEntity.getHeaders())
                    .body(pageResponse);
            }
        }
        
        // 處理直接返回 Page 的情況
        if (body instanceof org.springframework.data.domain.Page) {
            org.springframework.data.domain.Page<?> page = (org.springframework.data.domain.Page<?>) body;
            return convertPageToPageResponse(page);
        }
        
        return body;
    }
    
    /**
     * 將 Spring Data Page 轉換為 PageResponse（1-based pageNumber）
     */
    private PageResponse<?> convertPageToPageResponse(org.springframework.data.domain.Page<?> page) {
        PageResponse<?> pageResponse = PageResponseFactory.fromSpringPage(page);
        
        log.debug("轉換 Page 響應: 0-based pageNumber={} -> 1-based pageNumber={}", 
                page.getNumber(), pageResponse.getPageNumber());
        
        return pageResponse;
    }
}

