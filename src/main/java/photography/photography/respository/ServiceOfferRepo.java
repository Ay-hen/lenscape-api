package photography.photography.respository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.ServiceOffer;

public interface ServiceOfferRepo extends MongoRepository<ServiceOffer, String> {
    List<ServiceOffer> findByPhotographerId(String photographerId);

    List<ServiceOffer> findByPhotographerIdIn(Collection<String> photographerIds);

    List<ServiceOffer> findByStatus(String status);
}
