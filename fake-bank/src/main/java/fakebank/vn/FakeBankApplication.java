package fakebank.vn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FakeBankApplication {
    public static void main(String[] args) {
        SpringApplication.run(FakeBankApplication.class, args);
    }
}
