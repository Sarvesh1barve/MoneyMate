package com.moneymate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class MoneyMateApplication {
  public static void main(String[] args) {
    SpringApplication.run(MoneyMateApplication.class, args);
  }
}
