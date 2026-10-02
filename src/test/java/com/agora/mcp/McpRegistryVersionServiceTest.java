package com.agora.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class McpRegistryVersionServiceTest {
    @Test void runningIdentityCannotFollowMutableCheckoutOrEnvironment() {
        Map<String,Object> properties=new HashMap<>();properties.put("app.git.commit","runtime-commit");
        var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("fixture",properties));
        var service=new McpRegistryVersionService(provider(null),provider(null),env);
        properties.put("app.git.commit","later-docs-commit");
        assertEquals("runtime-commit",service.buildVersionInfo().get("gitCommit"));
        assertEquals("STARTUP_BUILD_OR_DEPLOYMENT_METADATA",service.buildVersionInfo().get("gitCommitSource"));
    }
    @Test void missingBuildMetadataFailsClosedInsteadOfReadingRepositoryHead() {
        var env=new StandardEnvironment();env.getPropertySources().remove("systemProperties");env.getPropertySources().remove("systemEnvironment");
        var service=new McpRegistryVersionService(provider(null),provider(null),env);
        assertEquals("unknown",service.buildVersionInfo().get("gitCommit"));
        assertEquals("UNKNOWN",service.buildVersionInfo().get("gitCommitSource"));
    }
    @Test void packagedBuildMetadataTakesPrecedence() {
        var p=new Properties();p.put("git.commit.id","1234567890abcdef");
        var service=new McpRegistryVersionService(provider(null),provider(new BuildProperties(p)),new StandardEnvironment());
        assertEquals("1234567890ab",service.buildVersionInfo().get("gitCommit"));
    }
    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {
        return (ObjectProvider<T>)Proxy.newProxyInstance(ObjectProvider.class.getClassLoader(),new Class<?>[]{ObjectProvider.class},
                (p,m,a)->m.getName().equals("orderedStream")?Stream.empty():value);
    }
}
