package photography.photography.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Reservation {
    @Id
    private String id;
    private String name;
    private String status;
    private String date;
    private String type;
    private String city;
    private String photographerName;
    private String phone;
    
    @JsonIgnore
    @DBRef
    private User user;

    @JsonIgnore
    @DBRef
    private User photographer;
}
