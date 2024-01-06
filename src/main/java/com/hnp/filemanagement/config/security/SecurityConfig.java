package com.hnp.filemanagement.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true, prePostEnabled = true)
public class SecurityConfig {


    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

//    @Bean
//    public InMemoryUserDetailsManager userDetailsManager() {
//        UserDetails user1= User.withUsername("user").password(passwordEncoder().encode("user")).roles("USER").build();
//        UserDetails user2= User.withUsername("admin").password(passwordEncoder().encode("admin")).roles("ADMIN").build();
//        return new InMemoryUserDetailsManager(user1, user2);
//    }


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {

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
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers("/files/public-files/**").permitAll();
                            auth.requestMatchers("/files/public-download/**").permitAll();
                            auth.requestMatchers("/favicon.ico").permitAll();
                            auth.requestMatchers("/webjars/**").permitAll();
                            auth.requestMatchers("/css/**").permitAll();
                            auth.requestMatchers("/js/**").permitAll();

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
                .build();


    }


}
