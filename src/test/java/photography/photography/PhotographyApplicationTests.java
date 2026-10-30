package photography.photography;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full application start-up smoke test. It needs a reachable MongoDB, so it only runs when MONGODB_URI is set, e.g.
 * {@code docker compose up -d mongo && MONGODB_URI=mongodb://localhost:27017 ./mvnw test}.
 */
@SpringBootTest(properties = {"app.jwt.allow-ephemeral-secret=true", "app.kafka.enabled=false", "app.upload.dir=target/test-uploads"})
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
class PhotographyApplicationTests {

	@Test
	void contextLoads() {
	}

}
