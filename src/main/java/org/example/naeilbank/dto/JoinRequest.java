package org.example.naeilbank.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Setter @Getter
public class JoinRequest {
    private String email;
    private String password;
}
