-- FaceDesk mysql schema: run ONCE on an empty dedicated database/schema.
CREATE TABLE face_metadata (id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL, feature_space_id VARCHAR(512), revision BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO face_metadata(id,schema_version,feature_space_id,revision) VALUES(1,1,NULL,0);
CREATE TABLE face_records (role VARCHAR(128) NOT NULL, code VARCHAR(128) NOT NULL, name VARCHAR(128), image_base64 LONGTEXT, feature VARBINARY(2048) NOT NULL, feature_space_id VARCHAR(512) NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL, PRIMARY KEY(role,code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE face_reference_samples (id VARCHAR(64) PRIMARY KEY,role VARCHAR(128) NOT NULL,code VARCHAR(128) NOT NULL,image LONGTEXT NOT NULL,feature VARBINARY(2048) NOT NULL,created_at VARCHAR(40) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX idx_reference_person ON face_reference_samples(role,code);
CREATE TABLE facedesk_nodes (node_id VARCHAR(64) PRIMARY KEY, heartbeat_ms BIGINT NOT NULL, feature_space_id VARCHAR(512) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE recognition_history (id VARCHAR(64) PRIMARY KEY,time_ms BIGINT NOT NULL,source VARCHAR(16) NOT NULL,status VARCHAR(32) NOT NULL,kind VARCHAR(16) NOT NULL,task_id VARCHAR(128),task_state VARCHAR(32),task_error VARCHAR(1024),name VARCHAR(128),code VARCHAR(128),source_name VARCHAR(512),person_role VARCHAR(128),node_id VARCHAR(64),payload LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX idx_history_time ON recognition_history(time_ms,id);
CREATE INDEX idx_history_task ON recognition_history(task_id);
CREATE INDEX idx_history_filter ON recognition_history(source,status,time_ms);
CREATE INDEX idx_history_owner ON recognition_history(node_id,task_state);
