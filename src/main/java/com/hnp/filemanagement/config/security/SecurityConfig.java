package com.hnp.filemanagement.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true, prePostEnabled = true)
public class SecurityConfig {

    /** Sent with every 401 on the API chain, so a client knows which scheme to retry with. */
    private static final String BASIC_CHALLENGE = "Basic realm=\"file-management\", charset=\"UTF-8\"";

    @Value("${filemanagement.auth.ldap.activedirectory.enabled:false}")
    private boolean activeDirectoryEnabled;



    private final BCryptPasswordEncoder passwordEncoder;

    private final UserDetailsService userDetailsService;

    private final ActiveDirectoryCustomAuthenticationProvider activeDirectoryCustomAuthenticationProvider;

    public SecurityConfig(BCryptPasswordEncoder passwordEncoder, UserDetailsService userDetailsService, ActiveDirectoryCustomAuthenticationProvider activeDirectoryCustomAuthenticationProvider) {
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
        this.activeDirectoryCustomAuthenticationProvider = activeDirectoryCustomAuthenticationProvider;
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        // Spring Security 7 removed the no-arg constructor and the setUserDetailsService setter:
        // the UserDetailsService is now mandatory and supplied at construction.
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        return daoAuthenticationProvider;
    }

//    @Bean
//    public AuthenticationProvider activeDirectoryLdapAuthenticationProvider() {
//
//        org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider activeDirectoryLdapAuthenticationProvider =
//                new org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider( "hnp.local", "ldap://172.29.76.9");
//
//        // to parse AD failed credentails error message due to account - expiry,lock, credentialis - expiry,lock
//        activeDirectoryLdapAuthenticationProvider.setConvertSubErrorCodesToExceptions(true);
//
//        return activeDirectoryLdapAuthenticationProvider;
//    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity httpSecurity) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder = httpSecurity.getSharedObject(AuthenticationManagerBuilder.class);

        if(activeDirectoryEnabled) {
            authenticationManagerBuilder.authenticationProvider(activeDirectoryCustomAuthenticationProvider)
                    .authenticationProvider(daoAuthenticationProvider());
//                .authenticationProvider(activeDirectoryLdapAuthenticationProvider());
        } else {
            authenticationManagerBuilder.authenticationProvider(daoAuthenticationProvider());
        }


        return authenticationManagerBuilder.build();
    }




//    @Bean
//    public InMemoryUserDetailsManager userDetailsManager() {
//        UserDetails user1= User.withUsername("user").password(passwordEncoder().encode("user")).roles("USER").build();
//        UserDetails user2= User.withUsername("admin").password(passwordEncoder().encode("admin")).roles("ADMIN").build();
//        return new InMemoryUserDetailsManager(user1, user2);
//    }


    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity, AuthenticationManager authenticationManager) throws Exception {

//        return httpSecurity
//                .csrf(csrf -> csrf.disable())
//                .cors(cors -> cors.disable())
//                .authorizeHttpRequests(
//                        auth -> {
//                            auth.requestMatchers("/**").permitAll();
//                        }
//                )
//                .build();

        return httpSecurity
//                .csrf(csrf -> csrf.disable())
//                .cors(cors -> cors.disable())
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers("/files/public-files/**").permitAll();
                            auth.requestMatchers("/files/public-download/**").permitAll();
                            auth.requestMatchers("/").permitAll();
                            auth.requestMatchers("/favicon.ico").permitAll();
                            auth.requestMatchers("/webjars/**").permitAll();
                            auth.requestMatchers("/css/**").permitAll();
                            auth.requestMatchers("/js/**").permitAll();
                            // vendored third-party assets - stylesheets, scripts and the icon font
                            auth.requestMatchers("/vendor/**").permitAll();
                            auth.requestMatchers("/public-pages/**").permitAll();

                            auth.anyRequest().authenticated();
                        }
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        // "/" decides where a signed-in user actually belongs; see HomeController.
                        // Not alwaysUse, so a saved request still wins over the default target.
                        .defaultSuccessUrl("/")
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(
                        logout -> logout
                                .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern("/logout"))
                                .logoutSuccessUrl("/login?logout")
                                .invalidateHttpSession(true)
                                .clearAuthentication(true)
                                .deleteCookies("JSESSIONID")
                                .permitAll()
                )
                .requestCache(cache -> cache.requestCache(pageOnlyRequestCache()))
                .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"))
                .authenticationManager(authenticationManager)
                .build();


    }



    /**
     * Spring Security replays whatever request triggered the login prompt. Left unfiltered that can
     * be an AJAX call, a stylesheet or a JSON endpoint, and the user lands on raw JSON or a 404
     * after signing in. Only remember real page navigations; everything else falls through to the
     * form-login default target ("/").
     */
    private RequestCache pageOnlyRequestCache() {
        RequestMatcher pageNavigation = new AndRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/**"),
                new NegatedRequestMatcher(new OrRequestMatcher(
                        PathPatternRequestMatcher.pathPattern("/api/**"),
                        PathPatternRequestMatcher.pathPattern("/resource/**"),
                        PathPatternRequestMatcher.pathPattern("/vendor/**"),
                        PathPatternRequestMatcher.pathPattern("/css/**"),
                        PathPatternRequestMatcher.pathPattern("/js/**"),
                        PathPatternRequestMatcher.pathPattern("/public-pages/**"),
                        PathPatternRequestMatcher.pathPattern("/favicon.ico"),
                        PathPatternRequestMatcher.pathPattern("/login"),
                        PathPatternRequestMatcher.pathPattern("/logout"))),
                // jQuery sets this on every $.ajax call, so it rules out the UI's own REST traffic.
                request -> !"XMLHttpRequest".equals(request.getHeader("X-Requested-With")),
                request -> {
                    String accept = request.getHeader("Accept");
                    return accept == null || accept.contains("text/html");
                });

        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(pageNavigation);
        return requestCache;
    }

    /**
     * The machine-facing chain: HTTP Basic, stateless, no CSRF and no CORS.
     *
     * <p>The explicit {@link org.springframework.security.web.AuthenticationEntryPoint} is the whole
     * point of the {@code exceptionHandling} block, and it fixes a defect that was invisible from
     * the browser. {@code BasicAuthenticationEntryPoint} reports a failure with
     * {@code response.sendError(401)}, and {@code sendError} asks the servlet container for an ERROR
     * dispatch. That dispatch re-enters the filter chains as a request for {@code /error}, which no
     * longer matches {@code /api/**} — so the <em>session</em> chain handled it and its form-login
     * entry point turned the answer into {@code 302 Location: /login}. A caller with a bad password
     * got a redirect carrying a stale {@code WWW-Authenticate} header.
     *
     * <p>That was not merely untidy. {@code GET /login} answers 200, so any client that follows
     * redirects — Oracle's {@code UTL_HTTP}, which {@code apex_web_service} is built on, follows up
     * to three by default and re-issues them as GET — would see a final 200 and conclude that its
     * {@code DELETE} had succeeded when nothing had been deleted.
     *
     * <p>Writing the status with {@code setStatus} instead of {@code sendError} skips the error
     * dispatch entirely, so 401 stays 401.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity httpSecurity, AuthenticationManager authenticationManager) throws Exception {
        return httpSecurity
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .cors(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    auth.anyRequest().authenticated();
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Both, and they cover different failures. BasicAuthenticationFilter keeps its own
                // entry point and calls it when a credential is present but wrong;
                // ExceptionTranslationFilter uses the exceptionHandling one when there is no
                // credential at all. Setting only the second leaves a wrong password redirecting.
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorizedEntryPoint()))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                response.setStatus(HttpStatus.FORBIDDEN.value())))
                .authenticationManager(authenticationManager)
                .build();
    }

    /**
     * Answers 401 by writing the status directly.
     *
     * <p>{@code setStatus} rather than {@code sendError} on purpose: {@code sendError} asks the
     * container for an ERROR dispatch, and that dispatch re-enters the filter chains as a request
     * for {@code /error}, which does not match {@code /api/**}. The session chain then took over and
     * redirected to the login page.
     */
    private static AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authenticationException) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BASIC_CHALLENGE);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        };
    }

}
