package com.agora.dto.knowledge;

/** #379 — Java 21 record. Jackson deserializes via canonical constructor in Spring Boot 3. */
public record KnowledgeEntry(String title, String content, String source) {
}
