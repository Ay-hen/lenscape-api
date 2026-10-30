package photography.photography.model;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
@Document
public class User implements UserDetails {
    @Id
    private String username;
    private String name;
    private String email;
    private String phone;
    private String role;
    private String city;
    private String availability;
    private String price;
    private Boolean suspended;
    private String avatarUrl;
    private Instant createdAt;
    private Instant lastActiveAt;
    /** Null on accounts created before e-mail verification existed: those are treated as verified (grandfathered). */
    private Boolean emailVerified;
    private Instant emailVerifiedAt;

    @JsonIgnore
    private String password;

    @JsonIgnore
    @DBRef
    private Token token;

    @JsonIgnore
    @DBRef
    private List<Reservation> reservations;

    public User(){
        this.reservations = new ArrayList<>();
    }

    public void addReservation(Reservation reservation) {
        this.reservations.add(reservation);
    }

    public void removeReservation(Reservation reservation){
        this.reservations.remove(reservation);
    }

    public boolean isSuspended() {
        return Boolean.TRUE.equals(suspended);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(this.getRole()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** A suspended account can neither sign in nor keep using a token issued before the suspension. */
    @Override
    public boolean isAccountNonLocked() {
        return !isSuspended();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return !isSuspended();
    }

}
