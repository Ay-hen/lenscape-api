package photography.photography.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;

import photography.photography.account.AccountService.CustomerPatch;
import photography.photography.api.ApiException;
import photography.photography.model.User;
import photography.photography.respository.MiscRepos.CustomerExtrasRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.TokenRepo;
import photography.photography.respository.UserRepo;

class AccountServiceEmailTest {
    UserRepo users = mock(UserRepo.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    AccountService service = new AccountService(users, mock(PhotographerProfileRepo.class), mock(CustomerExtrasRepo.class), mock(TokenRepo.class), mock(PasswordEncoder.class), mongo);

    private void signedInCustomer() {
        when(users.findByUsername("carla")).thenReturn(Optional.of(User.builder().username("carla").name("Carla").email("carla@example.com").role("client").suspended(false).build()));
    }

    @Test
    void aVerifiedAddressCannotBeSwappedForAnUnverifiedOne() {
        signedInCustomer();
        ApiException e = assertThrows(ApiException.class, () -> service.updateCustomer("carla", new CustomerPatch(null, "someone.else@example.com", null, null, null, null)));
        assertEquals(400, e.status());
        assertNotNull(e.errors().get("email"));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    void savingTheSettingsFormWithTheUnchangedAddressStillWorks() {
        signedInCustomer();
        service.updateCustomer("carla", new CustomerPatch("Carla B", "Carla@Example.com", "0612345678", "Rabat", null, null));
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(User.class)); // name/phone/city saved
    }
}
