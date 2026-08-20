package org.example.naeilbank.domain.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.evidence.EvidenceDtos.RuleContent;
import org.example.naeilbank.domain.evidence.EvidenceDtos.SourceContent;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

import java.net.URI;
import java.net.URISyntaxException;

final class EvidencePolicy {
    private EvidencePolicy() {
    }

    static SourceContent validate(SourceContent content) {
        try {
            URI uri = new URI(content.doiUrl());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new AuthException(ErrorCode.INVALID_EVIDENCE_URL);
            }
        } catch (URISyntaxException e) {
            throw new AuthException(ErrorCode.INVALID_EVIDENCE_URL);
        }
        return content;
    }

    static RuleContent validate(RuleContent content) {
        if (!content.condition().isObject() || content.minutesDelta() == 0) {
            throw new AuthException(ErrorCode.INVALID_EVIDENCE_CONTENT);
        }
        return content;
    }

    static String json(ObjectMapper objectMapper, RuleContent content) {
        try {
            return objectMapper.writeValueAsString(content.condition());
        } catch (JsonProcessingException e) {
            throw new AuthException(ErrorCode.INVALID_EVIDENCE_CONTENT);
        }
    }
}
