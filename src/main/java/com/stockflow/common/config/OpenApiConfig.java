package com.stockflow.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The generated API document, and — the part that actually matters — the bearer-token scheme.
 *
 * <h2>Without this, Swagger UI cannot call a single protected endpoint</h2>
 *
 * <p>springdoc generates the paths and schemas by itself. What it cannot infer is that the API is
 * authenticated: with no security scheme declared, Swagger UI renders no "Authorize" button and
 * sends no {@code Authorization} header, so every "Try it out" returns 401. The usual workaround is
 * that people stop using Swagger UI and reach for curl or Postman — at which point the generated
 * document is documentation nobody exercises, and it rots.</p>
 *
 * <p>Twelve lines to keep the API explorable is a good trade.</p>
 *
 * <p>The scheme is applied globally with {@code addSecurityItem}. Strictly it is wrong for the
 * handful of public endpoints, which will show a padlock they do not need; the alternative is
 * annotating every one of the several hundred protected operations individually. Marking the few
 * public ones {@code @Operation(security = {})} is the escape hatch.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI stockflowOpenApi(
            @Value("${spring.application.name:stockflow}") String applicationName,
            @Value("${stockflow.api.version:1.0.0}") String apiVersion) {

        return new OpenAPI()
                .info(new Info()
                        .title("StockFlowCommerce API")
                        .version(apiVersion)
                        .description("""
                                Unified warehouse and e-commerce platform.

                                **Every response uses the same envelope** - `success`, `data` on                                 success; `errorCode`, `message`, `fieldErrors` and `correlationId`                                 on failure. Branch on `errorCode`, never on `message`: the code is                                 stable, the message is translated.

                                **Retries:** send an `Idempotency-Key` header on any POST, PUT,                                 PATCH or DELETE and it becomes safe to retry - a duplicate gets the                                 original response back rather than doing the work twice.

                                **Language:** send `Accept-Language: vi` or `en`. Vietnamese is the                                 default.
                                """)
                        .contact(new Contact()
                                .name("GFA26SE03")
                                .email("thanhtu.uong@bfcfurniture.com")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the access token only - Swagger adds "
                                        + "the \"Bearer \" prefix itself.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
