package photography.photography.respository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.Token;

public interface TokenRepo  extends MongoRepository<Token,String>{
    public Optional<Token> findById(String token);
    public void deleteById(String token);
    void deleteByUsername(String username);
    java.util.List<Token> findByUsername(String username);
}
