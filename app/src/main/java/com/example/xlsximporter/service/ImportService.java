package com.example.xlsximporter.service;

import com.example.xlsximporter.dto.ImportResponse;
import com.example.xlsximporter.dto.ParsedSheet;
import com.example.xlsximporter.exception.ImportException;
import com.example.xlsximporter.model.ImportLog;
import com.example.xlsximporter.repository.ImportLogRepository;
import com.example.xlsximporter.validation.XlsxValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full XLSX → ClickHouse import pipeline:
 * <ol>
 *   <li>Parse xlsx</li>
 *   <li>Validate column names, types and data cells</li>
 *   <li>Build DDL + INSERT scripts</li>
 *   <li><b>DDL (CREATE TABLE)</b> — executed on <b>both</b> ClickHouse nodes</li>
 *   <li><b>INSERT data</b> — executed on <b>Node 1 only</b>;
 *       {@code ReplicatedMergeTree} propagates to Node 2 automatically</li>
 *   <li>Save import log to PostgreSQL</li>
 * </ol>
 *
 * <p><b>Constructor injection with @Qualifier — why no Lombok here:</b><br>
 * {@code @RequiredArgsConstructor} generates constructor parameters in field-declaration
 * order but does <em>not</em> forward field-level {@code @Qualifier} annotations to those
 * parameters. Spring then sees two {@link JdbcTemplate} beans with no way to choose and
 * throws "required a single bean, but 2 were found". The solution is a hand-written
 * constructor where {@code @Qualifier} is placed directly on the {@link JdbcTemplate}
 * parameters — Spring resolves them by bean name unambiguously.
 */
@Slf4j
@Service
public class ImportService {

    private final XlsxParserService       parserService;
    private final XlsxValidator           validator;
    private final ClickHouseScriptBuilder scriptBuilder;
    private final ImportLogRepository     importLogRepository;
    private final JdbcTemplate            jdbcNode1;
    private final JdbcTemplate            jdbcNode2;

    @Value("${import.batch-size:1000}")
    private int batchSize;

    public ImportService(
            XlsxParserService                         parserService,
            XlsxValidator                             validator,
            ClickHouseScriptBuilder                   scriptBuilder,
            ImportLogRepository                       importLogRepository,
            @Qualifier("jdbcTemplateNode1") JdbcTemplate jdbcNode1,
            @Qualifier("jdbcTemplateNode2") JdbcTemplate jdbcNode2) {

        this.parserService       = parserService;
        this.validator           = validator;
        this.scriptBuilder       = scriptBuilder;
        this.importLogRepository = importLogRepository;
        this.jdbcNode1           = jdbcNode1;
        this.jdbcNode2           = jdbcNode2;
    }

    public ImportResponse importFile(MultipartFile file, String tableName) {

        // 1. Parse
        ParsedSheet sheet = parserService.parse(file);

        // 2. Validate (ValidationException → HTTP 400)
        validator.validate(sheet, tableName);

        // 3. Build scripts once — reused for DDL on both nodes, INSERT on Node 1
        String         createScript  = scriptBuilder.buildCreateScript(tableName, sheet);
        List<String>   insertColumns = scriptBuilder.buildInsertColumns(sheet);
        String         insertSql     = scriptBuilder.buildInsertSql(tableName, insertColumns);
        LocalDateTime  operationDttm = LocalDateTime.now();
        List<Object[]> batchArgs     = buildBatchArgs(sheet, operationDttm);

        log.info("Import start: table='{}', rows={}, cols={}",
                tableName, batchArgs.size(), insertColumns.size());

        // 4a. DDL → BOTH nodes (ensures table exists on each replica)
        executeDdlOnBothNodes(tableName, createScript);

        // 4b. INSERT → Node 1 only (ReplicatedMergeTree replicates to Node 2)
        executeInsertOnNode1(tableName, insertSql, batchArgs);

        // 5. Persist log to PostgreSQL
        ImportLog saved = importLogRepository.save(ImportLog.builder()
                .tableName(tableName)
                .operationDttm(operationDttm)
                .rowsInserted(batchArgs.size())
                .processedByNode("Node1 (DDL: Node1+Node2)")
                .sourceFilename(file.getOriginalFilename())
                .build());

        log.info("Import done: id={}, table='{}', rows={}", saved.getId(), tableName, batchArgs.size());

        return ImportResponse.builder()
                .id(saved.getId())
                .tableName(tableName)
                .rowsInserted(batchArgs.size())
                .columnCount(sheet.columnNames().size())
                .operationDttm(operationDttm)
                .processedByNode("Node1 (DDL: Node1+Node2)")
                .createScript(createScript)
                .build();
    }

    private void executeDdlOnBothNodes(String tableName, String createScript) {
        // Node 1 — mandatory, failure aborts the import
        try {
            jdbcNode1.execute(createScript);
            log.info("[DDL][Node1] OK for '{}'", tableName);
        } catch (Exception e) {
            throw new ImportException(
                    "[DDL][Node1] Failed for '" + tableName + "': " + e.getMessage(), e);
        }

        // Node 2 — best-effort; ReplicatedMergeTree will sync if this fails
        try {
            jdbcNode2.execute(createScript);
            log.info("[DDL][Node2] OK for '{}'", tableName);
        } catch (Exception e) {
            log.warn("[DDL][Node2] Failed (non-fatal, replication will sync): {}", e.getMessage());
        }
    }

    private void executeInsertOnNode1(String tableName, String insertSql, List<Object[]> batchArgs) {
        if (batchArgs.isEmpty()) {
            log.info("[INSERT][Node1] No rows to insert for '{}'", tableName);
            return;
        }

        int total   = batchArgs.size();
        int batches = (int) Math.ceil((double) total / batchSize);

        try {
            for (int b = 0; b < batches; b++) {
                int from = b * batchSize;
                int to   = Math.min(from + batchSize, total);
                jdbcNode1.batchUpdate(insertSql, batchArgs.subList(from, to));
                log.debug("[INSERT][Node1] batch {}/{} ({} rows) → '{}'",
                        b + 1, batches, to - from, tableName);
            }
            log.info("[INSERT][Node1] {} rows written to '{}'", total, tableName);
        } catch (Exception e) {
            throw new ImportException(
                    "[INSERT][Node1] Failed for '" + tableName + "': " + e.getMessage(), e);
        }
    }

    private List<Object[]> buildBatchArgs(ParsedSheet sheet, LocalDateTime operationDttm) {
        List<Object[]> result = new ArrayList<>(sheet.rows().size());
        for (List<String> rawRow : sheet.rows()) {
            result.add(scriptBuilder.buildRowValues(rawRow, sheet, operationDttm).toArray());
        }
        return result;
    }
}
