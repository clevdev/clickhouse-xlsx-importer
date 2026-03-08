package com.example.xlsximporter.validation;

import com.example.xlsximporter.dto.ParsedSheet;
import com.example.xlsximporter.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates column names, column types, and data cells parsed from an xlsx file.
 */
@Slf4j
@Component
public class XlsxValidator {

    private static final Pattern COLUMN_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final int MAX_COLUMN_NAME_LENGTH = 64;

    /**
     * Full validation of a ParsedSheet. Throws {@link ValidationException} on any error.
     */
    public void validate(ParsedSheet sheet, String tableName) {
        List<String> errors = new ArrayList<>();

        validateTableName(tableName, errors);
        validateColumnNames(sheet.columnNames(), errors);
        validateColumnTypes(sheet.columnTypes(), errors);
        validateData(sheet, errors);

        if (!errors.isEmpty()) {
            log.warn("Validation failed with {} error(s)", errors.size());
            throw new ValidationException(errors);
        }
        log.info("Validation passed: {} columns, {} data rows", sheet.columnNames().size(), sheet.rows().size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Table name
    // ─────────────────────────────────────────────────────────────────────────

    private void validateTableName(String tableName, List<String> errors) {
        if (tableName == null || tableName.isBlank()) {
            errors.add("Table name must not be blank");
            return;
        }
        if (!COLUMN_NAME_PATTERN.matcher(tableName).matches()) {
            errors.add("Table name '" + tableName + "' must start with a letter or underscore and contain only letters, digits, or underscores");
        }
        if (ClickHouseTypeRegistry.RESERVED_KEYWORDS.contains(tableName.toLowerCase())) {
            errors.add("Table name '" + tableName + "' is a reserved ClickHouse keyword");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Column names (row 1)
    // ─────────────────────────────────────────────────────────────────────────

    private void validateColumnNames(List<String> names, List<String> errors) {
        if (names == null || names.isEmpty()) {
            errors.add("No column names found in row 1 of the xlsx file");
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int col = i + 1;

            if (name == null || name.isBlank()) {
                errors.add("Column " + col + ": name is blank");
                continue;
            }
            if (name.length() > MAX_COLUMN_NAME_LENGTH) {
                errors.add("Column " + col + ": name '" + name + "' exceeds " + MAX_COLUMN_NAME_LENGTH + " characters");
            }
            if (!COLUMN_NAME_PATTERN.matcher(name).matches()) {
                errors.add("Column " + col + ": name '" + name
                        + "' is invalid — must start with letter/underscore and contain only letters, digits, underscores");
            }
            if (ClickHouseTypeRegistry.RESERVED_KEYWORDS.contains(name.toLowerCase())) {
                errors.add("Column " + col + ": name '" + name + "' is a reserved ClickHouse keyword");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Column types (row 2)
    // ─────────────────────────────────────────────────────────────────────────

    private void validateColumnTypes(List<String> types, List<String> errors) {
        if (types == null || types.isEmpty()) {
            errors.add("No column types found in row 2 of the xlsx file");
            return;
        }
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            int col = i + 1;
            if (type == null || type.isBlank()) {
                errors.add("Column " + col + ": type is blank");
                continue;
            }
            if (!ClickHouseTypeRegistry.isValidType(type)) {
                errors.add("Column " + col + ": type '" + type + "' is not a recognised ClickHouse type");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data cells (row 3+)
    // ─────────────────────────────────────────────────────────────────────────

    private void validateData(ParsedSheet sheet, List<String> errors) {
        List<String> types = sheet.columnTypes();
        List<List<String>> rows = sheet.rows();

        if (rows == null || rows.isEmpty()) {
            log.warn("No data rows found in xlsx (rows 3+)");
            return;
        }

        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int excelRow = r + 3; // offset: rows 1+2 are header

            for (int c = 0; c < types.size(); c++) {
                String type = types.get(c);
                String cellValue = c < row.size() ? row.get(c) : null;

                boolean nullable = ClickHouseTypeRegistry.isNullable(type);
                if (cellValue == null || cellValue.isBlank()) {
                    if (!nullable) {
                        errors.add("Row " + excelRow + ", col " + (c + 1)
                                + ": null/empty value not allowed for non-Nullable type '" + type + "'");
                    }
                    continue;
                }
                validateCellValue(excelRow, c + 1, cellValue, type, errors);
            }
        }
    }

    private void validateCellValue(int rowNum, int colNum, String value,
                                    String rawType, List<String> errors) {
        String base = ClickHouseTypeRegistry.extractBaseTypeLower(rawType)
                .replaceAll("\\(.*\\)", "").trim();

        String err = switch (base) {
            case "int8"   -> checkIntRange(value, Byte.MIN_VALUE, Byte.MAX_VALUE, rawType);
            case "int16"  -> checkIntRange(value, Short.MIN_VALUE, Short.MAX_VALUE, rawType);
            case "int32"  -> checkIntRange(value, Integer.MIN_VALUE, Integer.MAX_VALUE, rawType);
            case "int64"  -> checkLong(value, rawType);
            case "uint8"  -> checkIntRange(value, 0, 255, rawType);
            case "uint16" -> checkIntRange(value, 0, 65535, rawType);
            case "uint32" -> checkIntRange(value, 0, 4294967295L, rawType);
            case "uint64" -> checkULong(value, rawType);
            case "float32", "float64" -> checkDouble(value, rawType);
            case "bool", "boolean"    -> checkBool(value, rawType);
            case "date", "date32"     -> checkDate(value, rawType);
            case "datetime", "datetime64" -> checkDateTime(value, rawType);
            case "uuid"   -> checkUuid(value, rawType);
            default       -> null; // String, FixedString, Decimal — any non-null is OK
        };

        if (err != null) {
            errors.add("Row " + rowNum + ", col " + colNum + ": " + err);
        }
    }

    // ── type-specific checks ──────────────────────────────────────────────────

    private String checkIntRange(String v, long min, long max, String type) {
        try {
            long n = Long.parseLong(v.trim());
            if (n < min || n > max) return "value '" + v + "' out of range for " + type;
        } catch (NumberFormatException e) {
            return "value '" + v + "' is not a valid integer for " + type;
        }
        return null;
    }

    private String checkLong(String v, String type) {
        try { Long.parseLong(v.trim()); } catch (NumberFormatException e) {
            return "value '" + v + "' is not a valid Int64 for " + type;
        }
        return null;
    }

    private String checkULong(String v, String type) {
        try {
            long n = Long.parseUnsignedLong(v.trim());
            if (n < 0) return "value '" + v + "' is negative for UInt64";
        } catch (NumberFormatException e) {
            return "value '" + v + "' is not a valid UInt64 for " + type;
        }
        return null;
    }

    private String checkDouble(String v, String type) {
        try { Double.parseDouble(v.trim()); } catch (NumberFormatException e) {
            return "value '" + v + "' is not a valid float for " + type;
        }
        return null;
    }

    private String checkBool(String v, String type) {
        String lv = v.trim().toLowerCase();
        if (!lv.equals("true") && !lv.equals("false") && !lv.equals("1") && !lv.equals("0")) {
            return "value '" + v + "' is not a valid boolean (true/false/1/0) for " + type;
        }
        return null;
    }

    private String checkDate(String v, String type) {
        if (!DateParser.isValidDate(v.trim())) {
            return "value '" + v + "' is not a parseable date for " + type
                    + " (accepted: yyyy-MM-dd, MM/dd/yyyy, dd.MM.yyyy, dd-MM-yyyy)";
        }
        return null;
    }

    private String checkDateTime(String v, String type) {
        if (!DateParser.isValidDateTime(v.trim())) {
            return "value '" + v + "' is not a parseable datetime for " + type;
        }
        return null;
    }

    private String checkUuid(String v, String type) {
        try { java.util.UUID.fromString(v.trim()); }
        catch (IllegalArgumentException e) {
            return "value '" + v + "' is not a valid UUID for " + type;
        }
        return null;
    }
}
