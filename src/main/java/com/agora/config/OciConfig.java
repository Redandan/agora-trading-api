package com.agora.config;

import com.agora.config.properties.OciObjectStorageProperties;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class OciConfig {

    private final OciObjectStorageProperties props;

    private String region;

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("=== OCI Configuration Loaded ===");
        log.info("Namespace: '{}'", props.namespace());
        log.info("Bucket: '{}'", props.bucket());
        log.info("Compartment: '{}'", props.compartment());

        // 根據 namespace 自動判斷 region
        String namespace = props.namespace();
        if (namespace != null && !namespace.isEmpty()) {
            if (namespace.contains("ap-singapore")) {
                region = "ap-singapore-1";
            } else if (namespace.contains("us-ashburn")) {
                region = "us-ashburn-1";
            } else if (namespace.contains("us-phoenix") || namespace.contains("axty9qz6w9h2")) {
                region = "us-phoenix-1";
            } else if (namespace.contains("eu-frankfurt")) {
                region = "eu-frankfurt-1";
            } else if (namespace.contains("uk-london")) {
                region = "uk-london-1";
            } else {
                region = "us-phoenix-1"; // 預設值
            }
        } else {
            region = "us-phoenix-1"; // 預設值
        }

        log.info("Region: '{}'", region);
        log.info("================================");
    }

    public ObjectStorageClient createObjectStorageClient() {
        try {
            // 驗證配置是否正確載入
            if (props.namespace() == null || props.namespace().trim().isEmpty()) {
                throw new IllegalStateException("OCI namespace is not configured. Please check application-oci.yml");
            }
            if (props.bucket() == null || props.bucket().trim().isEmpty()) {
                throw new IllegalStateException("OCI bucket is not configured. Please check application-oci.yml");
            }

            log.info("Configuration validation passed - Namespace: {}, Bucket: {}", props.namespace(), props.bucket());

            // 使用 Instance Principal 認證
            InstancePrincipalsAuthenticationDetailsProvider provider =
                InstancePrincipalsAuthenticationDetailsProvider.builder().build();

            ObjectStorageClient client = new ObjectStorageClient(provider);

            // 構建 endpoint
            String endpoint = "https://objectstorage." + region + ".oraclecloud.com";

            client.setEndpoint(endpoint);

            log.info("OCI Object Storage client initialized successfully with Instance Principal");
            log.info("Using endpoint: {}", endpoint);
            return client;
        } catch (Exception e) {
            log.error("Failed to initialize OCI Object Storage client", e);
            throw new RuntimeException("Failed to initialize OCI client", e);
        }
    }

    public String getNamespace() {
        return props.namespace();
    }

    public String getBucket() {
        return props.bucket();
    }

    public String getCompartment() {
        return props.compartment();
    }

    public String getRegion() {
        return region;
    }
}
