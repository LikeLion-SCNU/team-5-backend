package org.example.naeilbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NaeilBankBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(NaeilBankBackApplication.class, args);
    }
}
