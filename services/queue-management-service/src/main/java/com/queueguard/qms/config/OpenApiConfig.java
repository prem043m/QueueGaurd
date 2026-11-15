package com.queueguard.qms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI qmsOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer qmsBearerSecurity() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> {
                if (isProtectedApiPath(path)) {
                    for (var operation : pathItem.readOperations()) {
                        operation.setSecurity(List.of(
                                new SecurityRequirement().addList(BEARER_SCHEME)));
                    }
                }
            });
        };
    }

    private static boolean isProtectedApiPath(String path) {
        return path.startsWith("/api/v1/") || path.startsWith("/v1/");
    }
}
