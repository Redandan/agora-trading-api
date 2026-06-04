package com.agora.util;

import java.util.Random;

public class VerifyCodeUtils {
    private static final String CHARACTERS = "0123456789";
    private static final int CODE_LENGTH = 6;
    private static final Random RANDOM = new Random();

    /**
     * 生成驗證碼 6位數
     *
     * @return
     */
    public static String generateVerifyCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return code.toString();
    }

    public static void main(String[] args) {
        System.out.println(generateVerifyCode());
    }
}
