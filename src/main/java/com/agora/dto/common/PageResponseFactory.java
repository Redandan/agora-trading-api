package com.agora.dto.common;

import org.springframework.data.domain.Page;

/**
 * PageResponse 工廠類
 * 提供創建 PageResponse 的方法，供 PageResponseAdvice 使用
 * 
 * ⚠️ 此類僅供 PageResponseAdvice 使用，不應該在其他地方使用
 */
public class PageResponseFactory {
    
    /**
     * 從 Spring Data Page 創建 PageResponse（將 0-based pageNumber 轉換為 1-based）
     * 
     * @param page Spring Data Page 對象（內部使用 0-based pageNumber）
     * @return PageResponse 對象（包含 1-based pageNumber）
     */
    public static <T> PageResponse<T> fromSpringPage(Page<T> page) {
        // 將 0-based pageNumber 轉換為 1-based
        int oneBasedPageNumber = page.getNumber() + 1;
        
        // 創建 PageResponse
        PageResponse<T> response = PageResponse.of(
            page.getContent(),
            oneBasedPageNumber,
            page.getSize(),
            page.getTotalElements()
        );
        
        // 使用 Spring Data Page 的 totalPages（更準確）
        response.setTotalPages(page.getTotalPages());
        response.setFirst(page.isFirst());
        response.setLast(page.isLast());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
        response.setNumberOfElements(page.getNumberOfElements());
        response.setEmpty(page.isEmpty());
        response.setPageNumber(oneBasedPageNumber);
        
        return response;
    }
}

