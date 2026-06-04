package com.agora.util;

import com.agora.dto.tron.TronAddressInfo;
import com.agora.dto.tron.TronConfirmationRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TronGridUtils {

    private static final String TRONGRID_API_URL = "https://api.trongrid.io";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String USDT_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        // String txHash = "86ad1d3a557047fb9c39c9fb5ca40695f2e48771df23376bee620b504b364a65";
        // System.out.println(TronGridUtils.getAddressInfo("TBMS7WrUGKUtGFSXaNgMjcVuhqgjk24ZN1"));
        System.out.println(TronGridUtils.getTransactionByTxHash("9fc5b4980831f92917ce60b23d301135093e08a1fac627652e92be9c07cae4a6"));
    }

    /**
     * 驗證TRON地址是否有效
     * @param address TRON地址
     * @return 地址是否有效
     */
    public static boolean validateAddress(String address) throws IOException {
        String jsonBody = String.format("{\"address\":\"%s\",\"visible\":true}", address);
        RequestBody body = RequestBody.create(jsonBody, JSON);

        Request request = new Request.Builder()
                .url(TRONGRID_API_URL + "/wallet/validateaddress")
                .post(body)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("TronGridUtils.validateAddress Unexpected response code: {}", response);
                return false;
            }

            String responseBody = response.body().string();
            JsonNode rootNode = objectMapper.readTree(responseBody);
            
            // 檢查結果
            return rootNode.has("result") && rootNode.get("result").asBoolean();
        }
    }

    public static TronAddressInfo getAddressInfo(String address) throws IOException {
        log.info("開始調用TronGrid API獲取地址信息 - 地址: {}", address);
        
        Request request = new Request.Builder()
                .url(TRONGRID_API_URL + "/v1/accounts/" + address)
                .get()
                .build();

        log.debug("TronGrid API請求URL: {}", TRONGRID_API_URL + "/v1/accounts/" + address);

        try (Response response = httpClient.newCall(request).execute()) {
            log.debug("TronGrid API響應狀態碼: {}", response.code());
            
            if (!response.isSuccessful()) {
                log.error("TronGrid API調用失敗 - 地址: {}, 狀態碼: {}, 響應: {}", 
                        address, response.code(), response.message());
                return null;
            }

            String responseBody = response.body().string();
            log.debug("TronGrid API響應內容長度: {} 字符", responseBody.length());
            
            // 記錄響應內容用於調試（僅在debug級別）
            if (log.isDebugEnabled()) {
                log.debug("TronGrid API響應內容: {}", responseBody);
            }

            JsonNode rootNode = objectMapper.readTree(responseBody);
            
            // 檢查響應結構
            if (!rootNode.has("data") || rootNode.get("data") == null || !rootNode.get("data").isArray() || rootNode.get("data").size() == 0) {
                log.warn("TronGrid API響應結構異常 - 地址: {}, 響應: {}", address, responseBody);
                // 返回默認信息而不是拋出異常
                TronAddressInfo info = new TronAddressInfo();
                info.setAddress(address);
                info.setTrxBalance(BigDecimal.ZERO);
                info.setUsdtBalance(BigDecimal.ZERO);
                return info;
            }
            
            JsonNode data = rootNode.get("data").get(0);
            if (data == null) {
                log.warn("TronGrid API響應數據為空 - 地址: {}", address);
                TronAddressInfo info = new TronAddressInfo();
                info.setAddress(address);
                info.setTrxBalance(BigDecimal.ZERO);
                info.setUsdtBalance(BigDecimal.ZERO);
                return info;
            }

            TronAddressInfo info = new TronAddressInfo();
            info.setAddress(address);

            // 解析 TRX 餘額（單位：SUN，1 TRX = 1,000,000 SUN）
            if (data.has("balance") && data.get("balance") != null) {
                try {
                    BigDecimal trxBalance = new BigDecimal(data.get("balance").asText())
                            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.DOWN);
                    info.setTrxBalance(trxBalance);
                    log.debug("解析TRX餘額成功 - 地址: {}, 餘額: {}", address, trxBalance);
                } catch (NumberFormatException e) {
                    log.warn("TRX餘額格式異常 - 地址: {}, 餘額值: {}, 錯誤: {}", address, data.get("balance").asText(), e.getMessage());
                    info.setTrxBalance(BigDecimal.ZERO);
                }
            } else {
                log.debug("TRX餘額字段不存在 - 地址: {}", address);
                info.setTrxBalance(BigDecimal.ZERO);
            }

            // 解析 TRC20 代幣餘額
            if (data.has("trc20")) {
                JsonNode trc20Node = data.get("trc20");
                if (trc20Node != null && trc20Node.isArray() && trc20Node.size() > 0) {
                    JsonNode trc20 = trc20Node.get(0);
                    if (trc20 != null && trc20.has(USDT_CONTRACT)) {
                        try {
                            // USDT 精度為 6 位小數
                            BigDecimal usdtBalance = new BigDecimal(trc20.get(USDT_CONTRACT).asText())
                                    .movePointLeft(6);
                            info.setUsdtBalance(usdtBalance);
                            log.debug("解析USDT餘額成功 - 地址: {}, 餘額: {}", address, usdtBalance);
                        } catch (NumberFormatException e) {
                            log.warn("USDT餘額格式異常 - 地址: {}, 餘額值: {}, 錯誤: {}", address, trc20.get(USDT_CONTRACT).asText(), e.getMessage());
                            info.setUsdtBalance(BigDecimal.ZERO);
                        }
                    } else {
                        log.debug("USDT餘額字段不存在 - 地址: {}", address);
                        info.setUsdtBalance(BigDecimal.ZERO);
                    }
                } else {
                    log.debug("TRC20數組為空或無效 - 地址: {}", address);
                    info.setUsdtBalance(BigDecimal.ZERO);
                }
            } else {
                log.debug("TRC20字段不存在 - 地址: {}", address);
                info.setUsdtBalance(BigDecimal.ZERO);
            }

            log.info("TronGrid API調用成功 - 地址: {}, TRX餘額: {}, USDT餘額: {}", 
                    address, info.getTrxBalance(), info.getUsdtBalance());
            return info;
        } catch (Exception e) {
            log.error("TronGrid API調用異常 - 地址: {}, 錯誤: {}", address, e.getMessage(), e);
            throw e;
        }
    }


    public static List<TronConfirmationRecord> getTransactionByTxHash(String txHash) {
        List<TronConfirmationRecord> records = new ArrayList<>();
        TronConfirmationRecord record = new TronConfirmationRecord();

        String jsonBody = "{\"value\":\"" + txHash + "\"}";
        RequestBody body = RequestBody.create(jsonBody, JSON);

        Request request = new Request.Builder()
                .url(TRONGRID_API_URL + "/walletsolidity/gettransactionbyid")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("TronGridUtils.getTransactionByTxHash Unexpected response code: {}", response);
                return null;
            }

            String responseBody = response.body().string();
            JsonNode rootNode = objectMapper.readTree(responseBody);

            // 1. 解析交易狀態
            String status = rootNode.get("ret").get(0).get("contractRet").asText();
            record.setResult(status);

            // 2. 解析交易ID
            record.setHash(rootNode.get("txID").asText());

            // 3. 解析合約數據
            JsonNode rawData = rootNode.get("raw_data");
            JsonNode contract = rawData.get("contract").get(0);
            JsonNode parameter = contract.get("parameter");
            JsonNode value = parameter.get("value");

            String data = value.get("data").asText();

            if (!data.startsWith("a9059cbb")) { // 如果不是transfer方法
                return null;
            }

            String fromAddressHex = value.get("owner_address").asText();
            String contractAddress = value.get("contract_address").asText();
            String contractAddressBase58 = TronAddressUtils.hexToBase58(contractAddress);

            if (!USDT_CONTRACT.equals(contractAddressBase58)) {
                return null;
            }
            // 4. 解析時間戳
            long timestamp = rawData.get("timestamp").asLong();
            record.setTimestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.of("Asia/Taipei")));

            // 7. 解析transfer方法的參數
            record.setContractAddress(contractAddressBase58);
            record.setFrom(TronAddressUtils.hexToBase58(fromAddressHex));

            // 解析並轉換接收地址
            String toAddressHex = "41" + data.substring(32, 72);
            record.setTo(TronAddressUtils.hexToBase58(toAddressHex));

            // 解析金額
            String amountHex = data.substring(72);
            BigInteger amount = new BigInteger(amountHex, 16);
            BigDecimal usdtAmount = new BigDecimal(amount).movePointLeft(6);
            record.setAmount(usdtAmount);

            records.add(record);
            return records;
        } catch (IOException e) {
            log.error("TronGridUtils.getTransactionByTxHash IOException: {}", e.getMessage());
            return null;
        }
    }
}
