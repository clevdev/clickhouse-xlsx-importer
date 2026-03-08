package com.example.xlsximporter;

import com.example.xlsximporter.config.ClickHouseProperties;
import com.example.xlsximporter.dto.ParsedSheet;
import com.example.xlsximporter.exception.ValidationException;
import com.example.xlsximporter.service.ClickHouseScriptBuilder;
import com.example.xlsximporter.validation.ClickHouseTypeRegistry;
import com.example.xlsximporter.validation.DateParser;
import com.example.xlsximporter.validation.XlsxValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Pure unit tests — no Spring context, no database, no Docker.
 */
@ExtendWith(MockitoExtension.class)
class XlsxImporterUnitTests {

    @InjectMocks
    private XlsxValidator validator;

    private ClickHouseScriptBuilder scriptBuilder;

    @BeforeEach
    void setUp() {
        ClickHouseProperties props = new ClickHouseProperties();
        props.setUsePlainMergeTreeInTests(true);
        scriptBuilder = new ClickHouseScriptBuilder(props);
    }

    // ClickHouseTypeRegistry

    @Test @DisplayName("Valid bare ClickHouse types are accepted")
    void validBareTypes() {
        assertThat(ClickHouseTypeRegistry.isValidType("String")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Int64")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Float32")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Date")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("DateTime")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Bool")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("UUID")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("FixedString(10)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Decimal(18,4)")).isTrue();
    }

    @Test @DisplayName("Valid Nullable types are accepted")
    void validNullableTypes() {
        assertThat(ClickHouseTypeRegistry.isValidType("Nullable(String)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Nullable(Int64)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Nullable(Date)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Nullable(DateTime)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isValidType("Nullable(Float64)")).isTrue();
    }

    @Test @DisplayName("Invalid types are rejected")
    void invalidTypes() {
        assertThat(ClickHouseTypeRegistry.isValidType("VARCHAR")).isFalse();
        assertThat(ClickHouseTypeRegistry.isValidType("INTEGER")).isFalse();
        assertThat(ClickHouseTypeRegistry.isValidType("")).isFalse();
        assertThat(ClickHouseTypeRegistry.isValidType(null)).isFalse();
    }

    @Test @DisplayName("isDateType distinguishes Date from DateTime")
    void isDateType() {
        assertThat(ClickHouseTypeRegistry.isDateType("Date")).isTrue();
        assertThat(ClickHouseTypeRegistry.isDateType("Date32")).isTrue();
        assertThat(ClickHouseTypeRegistry.isDateType("Nullable(Date)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isDateType("DateTime")).isFalse();
        assertThat(ClickHouseTypeRegistry.isDateType("String")).isFalse();
    }

    @Test @DisplayName("isNullable identifies Nullable wrapper")
    void isNullable() {
        assertThat(ClickHouseTypeRegistry.isNullable("Nullable(String)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isNullable("Nullable(Date)")).isTrue();
        assertThat(ClickHouseTypeRegistry.isNullable("String")).isFalse();
        assertThat(ClickHouseTypeRegistry.isNullable("Date")).isFalse();
        assertThat(ClickHouseTypeRegistry.isNullable(null)).isFalse();
    }

    // DateParser

    @Test @DisplayName("All supported date formats parse correctly")
    void parseDateFormats() {
        assertThat(DateParser.parseDate("2004-05-25")).isPresent();
        assertThat(DateParser.parseDate("05/25/2004")).isPresent();
        assertThat(DateParser.parseDate("25.05.2004")).isPresent();
        assertThat(DateParser.parseDate("25-05-2004")).isPresent();
        assertThat(DateParser.parseDate("25/05/2004")).isPresent();
        assertThat(DateParser.parseDate("not-a-date")).isEmpty();
        assertThat(DateParser.parseDate(null)).isEmpty();
    }

    @Test @DisplayName("toClickHouseDateString normalises to yyyy-MM-dd")
    void dateStringNormalisation() {
        assertThat(DateParser.toClickHouseDateString("05/25/2004")).isEqualTo("2004-05-25");
        assertThat(DateParser.toClickHouseDateString("25.05.2004")).isEqualTo("2004-05-25");
        assertThat(DateParser.toClickHouseDateString("25-05-2004")).isEqualTo("2004-05-25");
    }

    //  XlsxValidator

    @Test @DisplayName("Valid sheet passes validation")
    void validationPassesForGoodSheet() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("id", "name", "event_date", "amount"),
                List.of("Int64", "String", "Date", "Nullable(Float64)"),
                List.of(List.of("1", "Alice", "2024-01-15", "99.5"),
                        List.of("2", "Bob",   "01/20/2024", ""))
        );
        assertThatNoException().isThrownBy(() -> validator.validate(sheet, "test_table"));
    }

    @Test @DisplayName("Reserved column name fails validation")
    void validationFailsForReservedColumnName() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("select"), List.of("String"), List.of(List.of("value")));
        assertThatThrownBy(() -> validator.validate(sheet, "my_table"))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getErrors())
                                .anyMatch(e -> e.contains("reserved")));
    }

    @Test @DisplayName("Invalid ClickHouse type fails validation")
    void validationFailsForBadType() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("col1"), List.of("VARCHAR"), List.of(List.of("value")));
        assertThatThrownBy(() -> validator.validate(sheet, "my_table"))
                .isInstanceOf(ValidationException.class);
    }

    @Test @DisplayName("Null in non-Nullable column fails validation")
    void validationFailsForNullInNonNullable() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("amount"), List.of("Int64"), List.of(Collections.singletonList(null)));
        assertThatThrownBy(() -> validator.validate(sheet, "my_table"))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getErrors())
                                .anyMatch(e -> e.contains("non-Nullable")));
    }

    @Test @DisplayName("Null in Nullable column passes validation")
    void validationAllowsNullInNullable() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("amount"), List.of("Nullable(Int64)"), List.of(Collections.singletonList(null)));
        assertThatNoException().isThrownBy(() -> validator.validate(sheet, "my_table"));
    }

    //  ClickHouseScriptBuilder

    @Test @DisplayName("Date → _str String (non-Nullable)")
    void nonNullableDateGetsStringCompanion() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("hire_date"), List.of("Date"), List.of());
        String ddl = scriptBuilder.buildCreateScript("employees", sheet);
        assertThat(ddl).contains("hire_date Date");
        assertThat(ddl).contains("hire_date_str String");
        assertThat(ddl).doesNotContain("hire_date_str Nullable");
    }

    @Test @DisplayName("Nullable(Date) → _str Nullable(String)")
    void nullableDateGetsNullableStringCompanion() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("opt_date"), List.of("Nullable(Date)"), List.of());
        String ddl = scriptBuilder.buildCreateScript("t", sheet);
        assertThat(ddl).contains("opt_date Nullable(Date)");
        assertThat(ddl).contains("opt_date_str Nullable(String)");
    }

    @Test @DisplayName("Nullable(DateTime) → _str Nullable(String)")
    void nullableDateTimeGetsNullableStringCompanion() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("updated_at"), List.of("Nullable(DateTime)"), List.of());
        String ddl = scriptBuilder.buildCreateScript("t", sheet);
        assertThat(ddl).contains("updated_at_str Nullable(String)");
    }

    @Test @DisplayName("DDL contains operation_dttm and MergeTree in test mode")
    void ddlContainsAuditAndEngine() {
        ParsedSheet sheet = new ParsedSheet(List.of("val"), List.of("String"), List.of());
        String ddl = scriptBuilder.buildCreateScript("t", sheet);
        assertThat(ddl).contains("operation_dttm DateTime");
        assertThat(ddl).contains("MergeTree");
    }

    @Test @DisplayName("buildInsertColumns includes _str companions and operation_dttm")
    void insertColumnsCorrect() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("name", "created", "opt_date"),
                List.of("String", "DateTime", "Nullable(Date)"),
                List.of());
        assertThat(scriptBuilder.buildInsertColumns(sheet)).containsExactly(
                "name", "created", "created_str", "opt_date", "opt_date_str", "operation_dttm");
    }

    @Test @DisplayName("buildInsertSql generates correct placeholders")
    void insertSqlPlaceholders() {
        String sql = scriptBuilder.buildInsertSql("t", List.of("a", "b", "c"));
        assertThat(sql).contains("INSERT INTO t").contains("(a, b, c)").contains("VALUES (?, ?, ?)");
    }

    @Test @DisplayName("buildRowValues: Date normalised, _str gets original")
    void rowValuesDateNormalisation() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("event_date"), List.of("Date"), List.of());
        List<Object> v = scriptBuilder.buildRowValues(
                List.of("05/25/2004"), sheet, LocalDateTime.of(2024, 1, 1, 12, 0));
        assertThat(v.get(0)).isEqualTo("2004-05-25");
        assertThat(v.get(1)).isEqualTo("05/25/2004");
        assertThat(v.get(2)).isEqualTo("2024-01-01 12:00:00");
    }

    @Test @DisplayName("buildRowValues: Nullable(Date) null → both null")
    void rowValuesNullableDate_null() {
        ParsedSheet sheet = new ParsedSheet(
                List.of("d"), List.of("Nullable(Date)"), List.of());
        List<Object> v = scriptBuilder.buildRowValues(
                Collections.singletonList(null), sheet, LocalDateTime.now());
        assertThat(v.get(0)).isNull();
        assertThat(v.get(1)).isNull();
    }
}
