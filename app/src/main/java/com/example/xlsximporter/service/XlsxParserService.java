package com.example.xlsximporter.service;

import com.example.xlsximporter.dto.ParsedSheet;
import com.example.xlsximporter.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an xlsx file into a {@link ParsedSheet}.
 * <p>
 * Row 1 → column names<br>
 * Row 2 → column types<br>
 * Row 3+ → data rows
 */
@Slf4j
@Service
public class XlsxParserService {

    /**
     * Parse the uploaded xlsx file.
     *
     * @param file multipart file
     * @return ParsedSheet
     * @throws ValidationException if the file format is incorrect
     */
    public ParsedSheet parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Uploaded file is empty or missing");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xlsm"))) {
            throw new ValidationException("Only .xlsx / .xlsm files are accepted, got: " + filename);
        }

        try (InputStream is = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new ValidationException("The xlsx file contains no sheets");
            }

            int totalRows = sheet.getPhysicalNumberOfRows();
            if (totalRows < 2) {
                throw new ValidationException(
                        "The xlsx file must have at least 2 rows (names + types), found: " + totalRows);
            }

            List<String> columnNames = parseRow(sheet.getRow(0));
            List<String> columnTypes = parseRow(sheet.getRow(1));

            if (columnNames.isEmpty()) {
                throw new ValidationException("Row 1 (column names) is empty");
            }
            if (columnTypes.size() < columnNames.size()) {
                throw new ValidationException(
                        "Row 2 (types) has fewer columns than row 1: " + columnTypes.size()
                        + " vs " + columnNames.size());
            }

            // Trim types list to match column count
            List<String> trimmedTypes = new ArrayList<>(columnTypes.subList(0, columnNames.size()));

            // Parse data rows (row index 2 onwards)
            List<List<String>> dataRows = new ArrayList<>();
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                dataRows.add(parseDataRow(row, columnNames.size()));
            }

            log.info("Parsed xlsx '{}': {} columns, {} data rows", filename,
                    columnNames.size(), dataRows.size());
            return new ParsedSheet(columnNames, trimmedTypes, dataRows);

        } catch (IOException e) {
            throw new ValidationException("Failed to read xlsx file: " + e.getMessage());
        }
    }

    /** Parse a header row — all cells as trimmed strings. */
    private List<String> parseRow(Row row) {
        if (row == null) return List.of();
        List<String> values = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(cellToString(cell));
        }
        // Remove trailing nulls/blanks from header rows
        while (!values.isEmpty() && (values.get(values.size() - 1) == null
                || values.get(values.size() - 1).isBlank())) {
            values.remove(values.size() - 1);
        }
        return values;
    }

    /** Parse a data row up to expectedCols columns. */
    private List<String> parseDataRow(Row row, int expectedCols) {
        List<String> values = new ArrayList<>();
        for (int c = 0; c < expectedCols; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(cellToString(cell));
        }
        return values;
    }

    /** Convert any cell type to its string representation, or null for blank/missing. */
    private String cellToString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> {
                String v = cell.getStringCellValue();
                yield (v == null || v.isBlank()) ? null : v.trim();
            }
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // Return ISO date or datetime
                    var localDate = cell.getLocalDateTimeCellValue();
                    if (localDate.getHour() == 0 && localDate.getMinute() == 0
                            && localDate.getSecond() == 0) {
                        yield localDate.toLocalDate().toString();
                    }
                    yield localDate.toString().replace("T", " ");
                }
                double d = cell.getNumericCellValue();
                // Avoid "1.0" for integers
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield String.valueOf((long) d);
                }
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                // Evaluate formula result
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> null;
        };
    }
}
