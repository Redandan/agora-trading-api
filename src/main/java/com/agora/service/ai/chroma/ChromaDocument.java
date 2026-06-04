package com.agora.service.ai.chroma;

import lombok.Data;

import java.util.Map;

@Data
public class ChromaDocument {
    private String id;
    private String document;
    private double distance;
    private Map<String, Object> metadata;
}
