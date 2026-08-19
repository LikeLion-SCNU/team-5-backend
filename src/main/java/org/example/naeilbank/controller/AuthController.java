package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.dto.JoinRequest;
import org.example.naeilbank.dto.JoinResponse;
import org.example.naeilbank.dto.LoginRequest;
import org.example.naeilbank.dto.LoginResponse;
import org.example.naeilbank.entity.User; // Entity 위치 확인 필요
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.example.naeilbank.global.security.CustomUserDetails;
import org.example.naeilbank.service.AuthService; // Service 위치 확인 필요
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtProvider;
    private final AuthenticationManager authenticationManager;
    private final AuthService authService;

    @GetMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest loginRequest) {

        System.out.println("test");

        // 1. 이메일/비밀번호 검증 및 인증 객체 생성
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword())
        );

        // 2. 인증 성공 시 CustomUserDetails에서 User 엔티티 추출
        CustomUserDetails userDetail = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetail.getUser();

        // 3. User 정보(userId, email, role)를 추출해 JWT 토큰 생성
        String token = jwtProvider.createToken(
                user.getId(),
                user.getEmail(),
                user.getRole().name()
        );

        // 4. 프론트엔드에 토큰과 기본 유저 정보(role 포함) 반환
        LoginResponse response = new LoginResponse(
                token,
                user.getEmail(),
                user.getRole().name()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/join")
    public ResponseEntity<JoinResponse> join(@RequestBody JoinRequest joinRequest) {
        JoinResponse response = authService.userRegister(joinRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/kakao")
    public ResponseEntity<?> kakaoLogin(@RequestBody Map<String, String> request){
        String code = request.get("code");

        if(code == null || code.isEmpty()){
            throw new AuthException(ErrorCode.INVALID_KAKAO_CODE);
        }
        LoginResponse response = authService.kakaoLogin(code);
        return ResponseEntity.ok(response);
    }
}
