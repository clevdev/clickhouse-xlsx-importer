package com.example.xlsximporter.it;

import com.example.xlsximporter.XlsxImporterApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for REST endpoints in ImportController.
 * Uses MockMvc against the full Spring context wired with Testcontainers.
 */
@SpringBootTest(classes = XlsxImporterApplication.class)
@AutoConfigureMockMvc
class ImportControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private MockMultipartFile toMock(MultipartFile f) throws Exception {
        return new MockMultipartFile("file", "test.xlsx", CONTENT_TYPE_XLSX, f.getBytes());
    }

    private String table() {
        return "ctrl_" + System.currentTimeMillis();
    }

    @Test
    @DisplayName("POST /xlsx returns 200 with correct body")
    void postXlsx_success() throws Exception {
        String tbl = table();
        MockMultipartFile file = toMock(XlsxTestHelper.buildXlsx(
                List.of("id", "label"),
                List.of("Int64", "String"),
                List.of(List.of(1, "a"), List.of(2, "b"))
        ));

        mockMvc.perform(multipart("/api/v1/import/xlsx")
                        .file(file).param("tableName", tbl))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tableName").value(tbl))
                .andExpect(jsonPath("$.rowsInserted").value(2))
                .andExpect(jsonPath("$.createScript").value(containsString("MergeTree")))
                .andExpect(jsonPath("$.operationDttm").isNotEmpty())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    @DisplayName("POST /xlsx with reserved column name → 400 VALIDATION_ERROR")
    void postXlsx_reservedColumn_400() throws Exception {
        MockMultipartFile file = toMock(XlsxTestHelper.buildXlsx(
                List.of("from"), List.of("String"), List.of(List.of("x"))));

        mockMvc.perform(multipart("/api/v1/import/xlsx")
                        .file(file).param("tableName", table()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value(containsString("reserved")));
    }

    @Test
    @DisplayName("POST /xlsx with invalid type → 400")
    void postXlsx_invalidType_400() throws Exception {
        MockMultipartFile file = toMock(XlsxTestHelper.buildXlsx(
                List.of("col1"), List.of("INTEGER"), List.of(List.of("1"))));

        mockMvc.perform(multipart("/api/v1/import/xlsx")
                        .file(file).param("tableName", table()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value(containsString("INTEGER")));
    }

    @Test
    @DisplayName("POST /xlsx with wrong data value → 400")
    void postXlsx_wrongDataValue_400() throws Exception {
        MockMultipartFile file = toMock(XlsxTestHelper.buildXlsx(
                List.of("count"), List.of("Int32"), List.of(List.of("not-an-int"))));

        mockMvc.perform(multipart("/api/v1/import/xlsx")
                        .file(file).param("tableName", table()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(containsString("not-an-int")));
    }

    @Test
    @DisplayName("POST /xlsx without tableName → 400")
    void postXlsx_missingTableName_400() throws Exception {
        MockMultipartFile file = toMock(XlsxTestHelper.buildXlsx(
                List.of("id"), List.of("Int64"), List.of(List.of(1))));

        mockMvc.perform(multipart("/api/v1/import/xlsx").file(file)) // no tableName
                .andExpect(status().is4xxClientError());
    }


    // GET /api/v1/import/logs

    @Test
    @DisplayName("GET /logs returns JSON array")
    void getLogs_ok() throws Exception {
        mockMvc.perform(get("/api/v1/import/logs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /logs/{table} returns log after import")
    void getLogsByTable_ok() throws Exception {
        String tbl = table();
        MockMultipartFile file = toMock(XlsxTestHelper.buildXlsx(
                List.of("x"), List.of("String"), List.of(List.of("hello"))));

        mockMvc.perform(multipart("/api/v1/import/xlsx")
                        .file(file).param("tableName", tbl))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/import/logs/" + tbl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tableName").value(tbl))
                .andExpect(jsonPath("$[0].rowsInserted").value(1));
    }


    // GET /api/v1/import/health

    @Test
    @DisplayName("GET /health returns 200 UP")
    void health_ok() throws Exception {
        mockMvc.perform(get("/api/v1/import/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
