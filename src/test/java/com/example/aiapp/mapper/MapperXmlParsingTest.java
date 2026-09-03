package com.example.aiapp.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MapperXmlParsingTest {

    @Test
    void allMapperXmlFilesCanBeParsed() throws Exception {
        Path mapperDirectory = Path.of("src/main/resources/mapper");
        List<Path> mapperFiles;
        try (var paths = Files.list(mapperDirectory)) {
            mapperFiles = paths.filter(path -> path.toString().endsWith(".xml")).toList();
        }
        for (Path mapperFile : mapperFiles) {
            assertDoesNotThrow(() -> {
                try (InputStream input = Files.newInputStream(mapperFile)) {
                    new XMLMapperBuilder(
                            input,
                            new Configuration(),
                            mapperFile.toString(),
                            new java.util.HashMap<>()
                    ).parse();
                }
            }, mapperFile::toString);
        }
    }
}
