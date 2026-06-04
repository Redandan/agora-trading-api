package com.agora.util;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;

import java.util.Arrays;

@Slf4j
public class TronAddressUtils {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * 將hex格式地址轉換為base58格式
     */
    public static String hexToBase58(String hexAddress) {
        try {
            // 1. 移除可能的"0x"或"41"前綴
            String cleanHex = hexAddress.replaceAll("^(0x|41)", "");

            // 2. 添加41前綴並轉換為字節數組
            byte[] addressBytes = Hex.decode("41" + cleanHex);

            // 3. 計算SHA256雙重哈希
            byte[] hash1 = sha256(addressBytes);
            byte[] hash2 = sha256(hash1);

            // 4. 取前4個字節作為校驗和
            byte[] checksum = Arrays.copyOfRange(hash2, 0, 4);

            // 5. 組合最終的字節數組
            byte[] finalBytes = new byte[addressBytes.length + 4];
            System.arraycopy(addressBytes, 0, finalBytes, 0, addressBytes.length);
            System.arraycopy(checksum, 0, finalBytes, addressBytes.length, 4);

            // 6. 進行Base58編碼
            return encodeBase58(finalBytes);
        } catch (Exception e) {
            log.error("Address conversion error for address: {}", hexAddress, e);
            return hexAddress;
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    private static String encodeBase58(byte[] input) {
        if (input.length == 0) {
            return "";
        }

        // 計算前導零的數量
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }

        // 複製輸入數組
        byte[] temp = Arrays.copyOf(input, input.length);

        // 初始化結果字符數組
        char[] result = new char[temp.length * 2];
        int resultLen = 0;

        // 進行Base58轉換
        for (int startAt = zeros; startAt < temp.length; ) {
            int mod = divmod58(temp, startAt);
            if (temp[startAt] == 0) {
                startAt++;
            }
            result[resultLen++] = ALPHABET.charAt(mod);
        }

        // 添加前導'1'（對應於前導零字節）
        while (zeros-- > 0) {
            result[resultLen++] = ALPHABET.charAt(0);
        }

        // 反轉結果
        for (int i = 0; i < resultLen / 2; i++) {
            char temp1 = result[i];
            result[i] = result[resultLen - 1 - i];
            result[resultLen - 1 - i] = temp1;
        }

        return new String(result, 0, resultLen);
    }

    private static int divmod58(byte[] number, int startAt) {
        int remainder = 0;
        for (int i = startAt; i < number.length; i++) {
            int digit256 = number[i] & 0xFF;
            int temp = remainder * 256 + digit256;
            number[i] = (byte) (temp / 58);
            remainder = temp % 58;
        }
        return remainder;
    }
    
    /**
     * 將Base58格式地址轉換為hex格式
     * @param base58Address Base58格式的Tron地址
     * @return hex格式地址（帶0x前綴）
     */
    public static String base58ToHex(String base58Address) {
        if (base58Address == null || base58Address.isEmpty()) {
            return null;
        }
        
        try {
            // Base58解碼
            byte[] decoded = decodeBase58(base58Address);
            
            if (decoded == null || decoded.length < 25) {
                log.error("Invalid Base58 address length: {}", base58Address);
                return null;
            }
            
            // 驗證校驗和
            byte[] addressBytes = Arrays.copyOfRange(decoded, 0, 21);
            byte[] checksum = Arrays.copyOfRange(decoded, 21, 25);
            
            // 計算校驗和
            byte[] hash1 = sha256(addressBytes);
            byte[] hash2 = sha256(hash1);
            byte[] calculatedChecksum = Arrays.copyOfRange(hash2, 0, 4);
            
            // 驗證校驗和
            if (!Arrays.equals(checksum, calculatedChecksum)) {
                log.error("Invalid checksum for address: {}", base58Address);
                return null;
            }
            
            // 提取地址字節（跳過前綴41）
            if (addressBytes[0] != 0x41) {
                log.error("Invalid address prefix: {}", addressBytes[0]);
                return null;
            }
            
            byte[] addressBytesWithoutPrefix = Arrays.copyOfRange(addressBytes, 1, 21);
            
            // 轉換為hex字符串（帶0x前綴）
            return "0x" + Hex.toHexString(addressBytesWithoutPrefix);
            
        } catch (Exception e) {
            log.error("Error converting Base58 to hex: {}", base58Address, e);
            return null;
        }
    }
    
    /**
     * Base58解碼
     */
    private static byte[] decodeBase58(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        
        // 計算前導1的數量
        int zeros = 0;
        while (zeros < input.length() && input.charAt(zeros) == '1') {
            zeros++;
        }
        
        // 轉換為BigInteger
        java.math.BigInteger num = java.math.BigInteger.ZERO;
        java.math.BigInteger base = java.math.BigInteger.valueOf(58);
        
        for (int i = zeros; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) {
                log.error("Invalid Base58 character: {}", c);
                return null;
            }
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit));
        }
        
        // 轉換為字節數組
        byte[] decoded = num.toByteArray();
        
        // 處理前導零
        if (decoded[0] == 0 && decoded.length > 1) {
            decoded = Arrays.copyOfRange(decoded, 1, decoded.length);
        }
        
        // 添加前導零
        byte[] result = new byte[zeros + decoded.length];
        System.arraycopy(decoded, 0, result, zeros, decoded.length);
        
        return result;
    }
} 