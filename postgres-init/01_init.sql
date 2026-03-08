-- PostgreSQL initialization script
-- Runs automatically on first start of the postgres container

-- Create the import log table (Spring JPA will also create it via ddl-auto=update,
-- but this ensures it's present before the app connects)
CREATE TABLE IF NOT EXISTS import_log (
    id               BIGSERIAL PRIMARY KEY,
    table_name       VARCHAR(128)  NOT NULL,
    operation_dttm   TIMESTAMP     NOT NULL,
    rows_inserted    INTEGER       NOT NULL DEFAULT 0,
    processed_by_node VARCHAR(32),
    source_filename  VARCHAR(256)
);

CREATE INDEX IF NOT EXISTS idx_import_log_table_name
    ON import_log (table_name);

CREATE INDEX IF NOT EXISTS idx_import_log_operation_dttm
    ON import_log (operation_dttm DESC);
