package com.agora.dto.auth;

import com.agora.enums.system.OAuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "登錄方式綁定列表")
public class LoginBindingsResponse {
    
    @Schema(description = "是否有密碼")
    private Boolean hasPassword;
    
    @Schema(description = "是否有郵箱")
    private Boolean hasEmail;
    
    @Schema(description = "郵箱是否已驗證")
    private Boolean emailVerified;
    
    @Schema(description = "是否可以使用郵箱登錄")
    private Boolean canUseEmailLogin;
    
    @Schema(description = "是否可以使用密碼登錄")
    private Boolean canUsePasswordLogin;
    
    @Schema(description = "OAuth綁定列表")
    private List<OAuthBindingInfo> oauthBindings;
    
    @Data
    @Schema(description = "OAuth綁定信息")
    public static class OAuthBindingInfo {
        @Schema(description = "OAuth提供商")
        private OAuthProvider provider;
        
        @Schema(description = "OAuth郵箱")
        private String email;
        
        @Schema(description = "OAuth用戶名")
        private String name;
        
        @Schema(description = "是否為主要綁定")
        private Boolean isPrimary;
        
        @Schema(description = "綁定時間")
        private LocalDateTime boundAt;
    }
}

