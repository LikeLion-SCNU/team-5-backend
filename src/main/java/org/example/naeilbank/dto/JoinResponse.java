package org.example.naeilbank.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter @Setter
public class JoinResponse {
    private Long userId;
    private String message;
}
