package com.example.aiapp.domain.vo;

public record SourceVO(
        String docId,
        String title,
        String chunk,
        String score,
        String backend,
        String hitModes,
        String chunkIndex,
        String parentTitle,
        String startChar,
        String endChar
) {
    public SourceVO(
            String docId,
            String title,
            String chunk,
            String score,
            String backend,
            String hitModes
    ) {
        this(docId, title, chunk, score, backend, hitModes, "", "", "", "");
    }
}
