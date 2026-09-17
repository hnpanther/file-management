package com.hnp.filemanagement.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document for the machine-facing API (roadmap 9.6).
 *
 * <p><b>Only {@code /api/**} is described.</b> The pages talk to {@code /resource/**}, and those
 * endpoints are an implementation detail of this application's own screens: their shapes change when
 * a screen changes, and publishing them in a document meant for outside callers would invite
 * somebody to build on a contract nobody promised to keep.
 *
 * <p><b>Two groups rather than one</b>, because there are two surfaces and they are not equivalent.
 * v1 is the shared machine account's API, authenticated with HTTP Basic, and it is what existing
 * integrations use. v2 is the S3-style object store, authenticated with an API key. Putting them in
 * one list would suggest they are versions of the same thing; they are two contracts that happen to
 * share a prefix.
 *
 * <p>Both security schemes are declared so that the "Authorize" button offers whichever the reader
 * actually holds. Neither is applied globally: each group's operations state their own requirement,
 * which is also what the document should say — a reader ought to see per operation what it needs.
 */
@Configuration
public class OpenApiConfig {

    /** Name of the bearer scheme, referenced by {@code @SecurityRequirement} on the v2 operations. */
    public static final String API_KEY_SCHEME = "apiKey";

    /** Name of the Basic scheme, for the v1 endpoints the shared machine account uses. */
    public static final String BASIC_SCHEME = "basicAuth";

    private final String applicationVersion;

    public OpenApiConfig(@Value("${spring.application.version:unknown}") String applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    @Bean
    public OpenAPI fileManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("File management API")
                        .version(applicationVersion)
                        .description("""
                                Machine-facing endpoints of the document management system.

                                **v2 is S3-*style*, not S3-compatible.** It borrows the shape — bucket, \
                                key, prefix, delimiter, continuation token, ETag — so that the API reads \
                                as familiar, but it does not implement AWS Signature V4 and answers in \
                                JSON rather than XML. `aws s3` and the AWS SDKs will not connect to it. \
                                Authenticate with `Authorization: Bearer fmk_…`, using a key issued on \
                                the API keys screen.

                                An API key reaches only the folders granted to it, never the folders \
                                granted to the person who created it."""))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("An API key: `Authorization: Bearer fmk_{keyId}_{secret}`"))
                        .addSecuritySchemes(BASIC_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("The shared machine account, for the v1 endpoints")));
    }

    /** The object store: buckets, keys, and the five operations on them. */
    @Bean
    public GroupedOpenApi objectStoreApiGroup() {
        return GroupedOpenApi.builder()
                .group("v2-object-store")
                .pathsToMatch("/api/v2/**")
                .addOpenApiCustomizer(api -> api.addSecurityItem(
                        new SecurityRequirement().addList(API_KEY_SCHEME)))
                .build();
    }

    /**
     * The original file API, kept for the integrations already built on it. Two security items
     * means "either": the shared account's password, or an API key, which reaches its own folders.
     */
    @Bean
    public GroupedOpenApi legacyFileApiGroup() {
        return GroupedOpenApi.builder()
                .group("v1-files")
                .pathsToMatch("/api/v1/**")
                .addOpenApiCustomizer(api -> api
                        .addSecurityItem(new SecurityRequirement().addList(BASIC_SCHEME))
                        .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME)))
                .build();
    }
}
