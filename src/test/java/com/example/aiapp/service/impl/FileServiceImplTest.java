package com.example.aiapp.service.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FileServiceImplTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "notes.txt", "guide.md", "report.PDF", "plan.docx",
            "metrics.xlsx", "legacy.xls", "workbook.xlsm", "deck.pptx"
    })
    void acceptsSupportedKnowledgeDocuments(String fileName) {
        assertThat(FileServiceImpl.isSupportedKnowledgeFile(fileName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"archive.zip", "slides.ppt", "document.doc", "README"})
    void rejectsUnsupportedKnowledgeDocuments(String fileName) {
        assertThat(FileServiceImpl.isSupportedKnowledgeFile(fileName)).isFalse();
    }
}
