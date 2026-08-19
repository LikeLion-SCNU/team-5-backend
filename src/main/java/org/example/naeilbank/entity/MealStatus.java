package org.example.naeilbank.entity;

public enum MealStatus {
    ANALYZING,        // AI 분석 중
    PENDING_CONFIRM,  // 사용자 확인 대기
    CONFIRMED,        // 최종 확정
    EXCLUDED          // 제외됨
}