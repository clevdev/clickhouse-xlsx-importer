package com.example.xlsximporter.it;

import com.example.xlsximporter.XlsxImporterApplication;
import com.example.xlsximporter.dto.ImportResponse;
import com.example.xlsximporter.exception.ValidationException;
import com.example.xlsximporter.model.ImportLog;
import com.example.xlsximporter.repository.ImportLogRepository;
import com.example.xlsximporter.service.ImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link ImportService}.
 *
 * <p>Each test gets a unique table name (timestamp-based) to avoid state conflicts.
 * Tests run against real containers started by {@link AbstractIntegrationTest}.
 */
@SpringBootTest(classes = XlsxImporterApplication.class)
class ImportServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportLogRepository importLogRepository;

    @Autowired @Qualifier("jdbcTemplateNode1") private JdbcTemplate jdbcNode1;
    @Autowired @Qualifier("jdbcTemplateNode2") private JdbcTemplate jdbcNode2;

    private String uniqueTable() {
        return "it_" + System.currentTimeMillis();
    }

    // Happy-path

    @Test
    @DisplayName("Import creates table and inserts rows on Node 1")
    void shouldImportDataToNode1() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("id", "name", "amount"),
                List.of("Int64", "String", "Float64"),
                List.of(
                        List.of(1, "Alice", 100.5),
                        List.of(2, "Bob",   200.75),
                        List.of(3, "Carol", 300.0)
                )
        );

        ImportResponse response = importService.importFile(file, table);

        assertThat(response.getRowsInserted()).isEqualTo(3);
        assertThat(response.getTableName()).isEqualTo(table);
        assertThat(response.getCreateScript()).contains("MergeTree");

        Integer countNode1 = jdbcNode1.queryForObject("SELECT count() FROM " + table, Integer.class);
        assertThat(countNode1).isEqualTo(3);
    }

    @Test
    @DisplayName("DDL is executed on both nodes (table exists on Node 1 AND Node 2)")
    void ddlExecutedOnBothNodes() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("val"), List.of("String"), List.of(List.of("hello"))
        );

        importService.importFile(file, table);

        // Table must exist on both nodes
        assertThatNoException().isThrownBy(() ->
                jdbcNode1.queryForObject("SELECT count() FROM " + table, Integer.class));
        assertThatNoException().isThrownBy(() ->
                jdbcNode2.queryForObject("SELECT count() FROM " + table, Integer.class));
    }

    @Test
    @DisplayName("INSERT goes to Node 1 only — Node 2 has 0 rows (no replication in test containers)")
    void insertOnNode1Only() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("id", "label"),
                List.of("Int64", "String"),
                List.of(List.of(1, "x"), List.of(2, "y"))
        );

        importService.importFile(file, table);

        // Node 1 has data
        Integer countNode1 = jdbcNode1.queryForObject("SELECT count() FROM " + table, Integer.class);
        assertThat(countNode1).isEqualTo(2);

        // Node 2 has the table (DDL) but no rows (no ZK replication in test containers)
        Integer countNode2 = jdbcNode2.queryForObject("SELECT count() FROM " + table, Integer.class);
        assertThat(countNode2).isEqualTo(0);
    }

    @Test
    @DisplayName("Import log is saved to PostgreSQL")
    void shouldSaveImportLogToPostgres() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("code", "label"),
                List.of("Int32", "String"),
                List.of(List.of(1, "one"), List.of(2, "two"))
        );

        ImportResponse response = importService.importFile(file, table);

        List<ImportLog> logs = importLogRepository.findByTableNameOrderByOperationDttmDesc(table);
        assertThat(logs).hasSize(1);
        ImportLog log = logs.get(0);
        assertThat(log.getId()).isEqualTo(response.getId());
        assertThat(log.getTableName()).isEqualTo(table);
        assertThat(log.getRowsInserted()).isEqualTo(2);
        assertThat(log.getSourceFilename()).isEqualTo("test.xlsx");
        assertThat(log.getProcessedByNode()).contains("Node1");
    }

    @Test
    @DisplayName("Nullable(Date) column gets Nullable(String) companion in DDL")
    void nullableDateCompanionType() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("event_date", "optional_date"),
                List.of("Date", "Nullable(Date)"),
                List.of(
                        List.of("2024-01-15", "2024-06-01"),
                        List.of("2024-02-20", "")
                )
        );

        ImportResponse response = importService.importFile(file, table);

        assertThat(response.getCreateScript()).contains("event_date_str String");
        assertThat(response.getCreateScript()).contains("optional_date_str Nullable(String)");

        // Verify _str values on Node 1
        List<Map<String, Object>> rows = jdbcNode1.queryForList(
                "SELECT event_date_str, optional_date_str FROM " + table + " ORDER BY event_date");
        assertThat(rows.get(0).get("event_date_str")).isEqualTo("2024-01-15");
        assertThat(rows.get(1).get("optional_date_str")).isNull();
    }

    @Test
    @DisplayName("Batch insert handles > 1000 rows correctly")
    void batchInsertLargeDataset() throws Exception {
        String table = uniqueTable();

        List<List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 2500; i++) {
            rows.add(List.of(i, "item_" + i, i * 1.5));
        }

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("id", "name", "value"),
                List.of("Int64", "String", "Float64"),
                rows
        );

        ImportResponse response = importService.importFile(file, table);
        assertThat(response.getRowsInserted()).isEqualTo(2500);

        Integer count = jdbcNode1.queryForObject("SELECT count() FROM " + table, Integer.class);
        assertThat(count).isEqualTo(2500);
    }

    @Test
    @DisplayName("Nullable fields accept null values")
    void nullableFieldsAcceptNull() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("id", "score", "note"),
                List.of("Int64", "Nullable(Float64)", "Nullable(String)"),
                List.of(
                        List.of(1, 9.5, "good"),
                        List.of(2, "",  ""),
                        List.of(3, 7.0, "ok")
                )
        );

        ImportResponse response = importService.importFile(file, table);
        assertThat(response.getRowsInserted()).isEqualTo(3);

        List<Map<String, Object>> data = jdbcNode1.queryForList(
                "SELECT id, score, note FROM " + table + " ORDER BY id");
        assertThat(data.get(1).get("score")).isNull();
        assertThat(data.get(1).get("note")).isNull();
    }

    @Test
    @DisplayName("operation_dttm is populated on every inserted row")
    void operationDttmPopulated() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("val"), List.of("String"), List.of(List.of("hello")));

        importService.importFile(file, table);

        List<Map<String, Object>> rows = jdbcNode1.queryForList(
                "SELECT operation_dttm FROM " + table);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("operation_dttm")).isNotNull();
    }

    @Test
    @DisplayName("Second import to same table appends rows (IF NOT EXISTS is idempotent)")
    void idempotentTableCreation() throws Exception {
        String table = uniqueTable();

        MultipartFile file1 = XlsxTestHelper.buildXlsx(
                List.of("id", "name"), List.of("Int64", "String"),
                List.of(List.of(1, "first")));
        MultipartFile file2 = XlsxTestHelper.buildXlsx(
                List.of("id", "name"), List.of("Int64", "String"),
                List.of(List.of(2, "second")));

        importService.importFile(file1, table);
        importService.importFile(file2, table);

        Integer count = jdbcNode1.queryForObject("SELECT count() FROM " + table, Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Multiple date formats are accepted and normalised to ISO")
    void multipleDateFormats() throws Exception {
        String table = uniqueTable();

        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("id", "event_date"),
                List.of("Int64", "Date"),
                List.of(
                        List.of(1, "2024-01-15"),   // yyyy-MM-dd
                        List.of(2, "01/20/2024"),   // MM/dd/yyyy
                        List.of(3, "25.03.2024"),   // dd.MM.yyyy
                        List.of(4, "10-04-2024")    // dd-MM-yyyy
                )
        );

        ImportResponse response = importService.importFile(file, table);
        assertThat(response.getRowsInserted()).isEqualTo(4);

        List<Map<String, Object>> rows = jdbcNode1.queryForList(
                "SELECT toString(event_date) AS d FROM " + table + " ORDER BY id");
        assertThat(rows).extracting(r -> r.get("d"))
                .containsExactly("2024-01-15", "2024-01-20", "2024-03-25", "2024-04-10");
    }

    // Validation failures

    @Test
    @DisplayName("Reserved column name → ValidationException")
    void reservedColumnName() throws Exception {
        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("select"), List.of("String"), List.of(List.of("x")));
        assertThatThrownBy(() -> importService.importFile(file, "valid_table"))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getErrors())
                                .anyMatch(e -> e.contains("reserved")));
    }

    @Test
    @DisplayName("Invalid type → ValidationException")
    void invalidType() throws Exception {
        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("col1"), List.of("VARCHAR"), List.of(List.of("x")));
        assertThatThrownBy(() -> importService.importFile(file, "t_" + System.currentTimeMillis()))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getErrors())
                                .anyMatch(e -> e.contains("VARCHAR")));
    }

    @Test
    @DisplayName("Non-numeric value in Int64 → ValidationException")
    void wrongDataType() throws Exception {
        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("id"), List.of("Int64"), List.of(List.of("not-a-number")));
        assertThatThrownBy(() -> importService.importFile(file, "t_" + System.currentTimeMillis()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Null in non-Nullable column → ValidationException")
    void nullInNonNullable() throws Exception {
        MultipartFile file = XlsxTestHelper.buildXlsx(
                List.of("required"), List.of("Int64"), List.of(List.of("")));
        assertThatThrownBy(() -> importService.importFile(file, "t_" + System.currentTimeMillis()))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getErrors())
                                .anyMatch(e -> e.contains("non-Nullable")));
    }
}
