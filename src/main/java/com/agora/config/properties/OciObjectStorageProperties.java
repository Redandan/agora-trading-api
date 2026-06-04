package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * #378 POC #2 — OCI Object Storage credentials.
 *
 * <p>Replaces 3 @Value injections in {@code OciConfig}. The {@code region}
 * field there is derived from {@link #namespace()} and stays in OciConfig.
 *
 * <p>Defaults are empty strings to preserve the prior behaviour where an
 * unset value parsed as {@code ""} (PostConstruct then errors out clearly).
 */
@ConfigurationProperties(prefix = "oci.objectstorage")
public record OciObjectStorageProperties(

        /** OCI namespace (used to derive region in OciConfig.init()). */
        @DefaultValue("") String namespace,

        /** Target bucket name. */
        @DefaultValue("") String bucket,

        /** OCI compartment OCID. */
        @DefaultValue("") String compartment
) {
}
