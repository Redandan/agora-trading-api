package com.agora.util;

public class MaskUtils {

    public static String blueString(String input) {
        if (input == null || input.length() <= 4) {
            return input; // 不足五個字元就原樣返回
        }
        String visiblePart = input.substring(0, 4);
        StringBuilder maskedPart = new StringBuilder();
        for (int i = 0; i < input.length() - 4; i++) {
            maskedPart.append("*");
        }
        return visiblePart + maskedPart.toString();
    }
    
}
