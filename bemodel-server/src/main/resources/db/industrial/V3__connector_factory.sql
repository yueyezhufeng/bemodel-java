-- Connector contracts are metadata drafts, separate from executable JDBC mappings.
CREATE TABLE fp_connector_config (
  code VARCHAR(64) NOT NULL PRIMARY KEY,
  document JSON NOT NULL,
  version INT NOT NULL DEFAULT 1,
  validated_version INT NULL,
  validation JSON NULL,
  updated_by VARCHAR(100) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fp_connector_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  connector_code VARCHAR(64) NOT NULL,
  operation VARCHAR(24) NOT NULL,
  version INT NOT NULL,
  actor VARCHAR(100) NULL,
  detail JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_fp_connector_log (connector_code, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
