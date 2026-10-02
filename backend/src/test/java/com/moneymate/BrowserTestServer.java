package com.moneymate;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Disposable browser-test database. This class is absent from the production jar. */
public class BrowserTestServer {
  public static void main(String[] args) throws Exception {
    Path marker = Path.of("../.local/pause-api").toAbsolutePath().normalize();
    Files.createDirectories(marker.getParent());
    try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(0).start()) {
      System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
      System.setProperty("spring.datasource.username", "postgres");
      System.setProperty("spring.datasource.password", "");
      System.setProperty("server.port", "8080");
      System.setProperty("server.address", "127.0.0.1");
      ConfigurableApplicationContext context = SpringApplication.run(MoneyMateApplication.class);
      while (true) {
        Thread.sleep(500);
        boolean pause = Files.exists(marker);
        if (pause && context != null) {
          context.close();
          context = null;
        } else if (!pause && context == null) {
          context = SpringApplication.run(MoneyMateApplication.class);
        }
      }
    }
  }
}
