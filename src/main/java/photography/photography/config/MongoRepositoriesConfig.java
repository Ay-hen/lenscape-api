package photography.photography.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Spring Data ignores repository interfaces nested inside other classes unless told otherwise.
 * The small repositories in MiscRepos are nested, so nested scanning must be enabled explicitly.
 */
@Configuration
@EnableMongoRepositories(basePackages = "photography.photography", considerNestedRepositories = true)
public class MongoRepositoriesConfig {
}