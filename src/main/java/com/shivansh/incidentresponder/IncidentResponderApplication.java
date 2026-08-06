package com.shivansh.incidentresponder;

import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IncidentResponderApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(IncidentResponderApplication.class);
        // spring-dotenv 5.x dropped its spring.factories, so the .env property source
        // has to be registered by hand. Without this line, ${GROQ_API_KEY} in
        // application.yml never sees the .env file.
        application.addInitializers(new DotenvApplicationInitializer());
        application.run(args);
    }
}
