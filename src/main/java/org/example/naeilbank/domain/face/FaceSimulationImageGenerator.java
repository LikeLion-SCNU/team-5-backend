package org.example.naeilbank.domain.face;

import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;

import java.util.List;

public interface FaceSimulationImageGenerator {
    FaceGenerationResult generate(
            InputImage sourceImage,
            String trendDescription,
            FaceSimulationOutput.Label requestedLabel
    );

    record InputImage(String contentType, byte[] content) {
    }

    record GeneratedImage(
            FaceSimulationOutput.Label label,
            String contentType,
            byte[] content
    ) {
    }

    record FaceGenerationResult(
            String modelVersion,
            String promptVersion,
            List<GeneratedImage> images
    ) {
    }
}
