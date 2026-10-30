package photography.photography.respository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.ServicePackage;

public interface ServicePackageRepo extends MongoRepository<ServicePackage, String> {
    List<ServicePackage> findByPhotographerId(String photographerId);

    List<ServicePackage> findByPhotographerIdIn(Collection<String> photographerIds);

    List<ServicePackage> findByServiceId(String serviceId);

    void deleteByServiceId(String serviceId);
}
