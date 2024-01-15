package com.hnp.filemanagement.config.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true, prePostEnabled = true)
public class SecurityConfig {



    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private CustomAuthenticationProvider customAuthenticationProvider;

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider();
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        daoAuthenticationProvider.setUserDetailsService(userDetailsService);
        return daoAuthenticationProvider;
    }

    @Bean
    public AuthenticationProvider activeDirectoryLdapAuthenticationProvider() {

        ActiveDirectoryLdapAuthenticationProvider activeDirectoryLdapAuthenticationProvider =
                new ActiveDirectoryLdapAuthenticationProvider( "hnp.local", "ldap://172.29.76.9");

        // to parse AD failed credentails error message due to account - expiry,lock, credentialis - expiry,lock
        activeDirectoryLdapAuthenticationProvider.setConvertSubErrorCodesToExceptions(true);

        return activeDirectoryLdapAuthenticationProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity httpSecurity) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder = httpSecurity.getSharedObject(AuthenticationManagerBuilder.class);
        authenticationManagerBuilder.authenticationProvider(customAuthenticationProvider)
                .authenticationProvider(daoAuthenticationProvider());
//                .authenticationProvider(activeDirectoryLdapAuthenticationProvider());
        return authenticationManagerBuilder.build();
    }




//    @Bean
//    public InMemoryUserDetailsManager userDetailsManager() {
//        UserDetails user1= User.withUsername("user").password(passwordEncoder().encode("user")).roles("USER").build();
//        UserDetails user2= User.withUsername("admin").password(passwordEncoder().encode("admin")).roles("ADMIN").build();
//        return new InMemoryUserDetailsManager(user1, user2);
//    }


    @Bean
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
                            auth.requestMatchers("/files/public-download/**").permitAll();
                            auth.requestMatchers("/").permitAll();
                            auth.requestMatchers("/favicon.ico").permitAll();
                            auth.requestMatchers("/webjars/**").permitAll();
                            auth.requestMatchers("/css/**").permitAll();
                            auth.requestMatchers("/js/**").permitAll();
                            auth.requestMatchers("/public-pages/**").permitAll();

                            auth.anyRequest().authenticated();
                        }
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .permitAll())
                .logout(
                        logout -> logout
                                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                                .permitAll()
                )
                .authenticationManager(authenticationManager)
                .build();


    }


}
