package com.agora.dto.auth;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class RegisterRequestParam {
    @NotNull
    private String username;
    @NotNull
    private String password;
    @NotNull
    private String email;
    @NotNull
    private String phone;
} 