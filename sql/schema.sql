BEGIN;
CREATE TABLE offices (
 id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL, kind VARCHAR(2) NOT NULL CHECK(kind IN ('GN','DS')),
 division VARCHAR(80) NOT NULL, parent_id UUID REFERENCES offices(id),
 CHECK ((kind='DS' AND parent_id IS NULL) OR (kind='GN' AND parent_id IS NOT NULL)),
 UNIQUE(name,division)
);
CREATE TABLE users (
 id UUID PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE,
 password_hash VARCHAR(60) NOT NULL CHECK(password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'),
 role VARCHAR(10) NOT NULL CHECK(role IN ('CITIZEN','GN','DS','ADMIN')),
 office_id UUID REFERENCES offices(id), division VARCHAR(80) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((role IN ('GN','DS') AND office_id IS NOT NULL) OR (role IN ('CITIZEN','ADMIN') AND office_id IS NULL))
);
CREATE TABLE requests (
 id UUID PRIMARY KEY, citizen_id UUID NOT NULL REFERENCES users(id),
 gn_id UUID NOT NULL REFERENCES users(id), ds_id UUID NOT NULL REFERENCES users(id),
 gn_office_id UUID NOT NULL REFERENCES offices(id), ds_office_id UUID NOT NULL REFERENCES offices(id),
 division VARCHAR(80) NOT NULL, type VARCHAR(30) NOT NULL CHECK(type IN ('RESIDENCE','INCOME','CHARACTER','OTHER')),
 title VARCHAR(160) NOT NULL, description VARCHAR(5000) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('SUBMITTED','RECEIVED_BY_GN','UNDER_GN_REVIEW','VERIFIED_BY_GN','NEEDS_CORRECTION','FORWARDED_TO_DS','RETURNED_TO_GN','APPROVED','REJECTED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version >= 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE request_history (
 id BIGSERIAL PRIMARY KEY, request_id UUID NOT NULL REFERENCES requests(id), actor_id UUID NOT NULL REFERENCES users(id),
 from_status VARCHAR(30), to_status VARCHAR(30) NOT NULL, action VARCHAR(30) NOT NULL, reason VARCHAR(1000),
 version BIGINT NOT NULL CHECK(version>=0), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(request_id,version)
);
CREATE TABLE documents (
 id UUID PRIMARY KEY, request_id UUID NOT NULL REFERENCES requests(id), uploaded_by UUID NOT NULL REFERENCES users(id),
 original_name VARCHAR(180) NOT NULL, storage_name VARCHAR(80) NOT NULL UNIQUE,
 media_type VARCHAR(80) NOT NULL, size_bytes BIGINT NOT NULL CHECK(size_bytes>0),
 sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE messages (
 id UUID PRIMARY KEY, request_id UUID NOT NULL REFERENCES requests(id), sender_id UUID NOT NULL REFERENCES users(id),
 body VARCHAR(4000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE notifications (
 id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), request_id UUID NOT NULL REFERENCES requests(id),
 event_type VARCHAR(40) NOT NULL, body VARCHAR(1000) NOT NULL, read_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE login_logs (
 id BIGSERIAL PRIMARY KEY, user_id UUID REFERENCES users(id), success BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE audit_logs (
 id BIGSERIAL PRIMARY KEY, actor_id UUID REFERENCES users(id), request_id UUID REFERENCES requests(id),
 action VARCHAR(80) NOT NULL, outcome VARCHAR(40) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX users_office_role_idx ON users(office_id,role) WHERE active;
CREATE INDEX requests_citizen_idx ON requests(citizen_id,created_at);
CREATE INDEX requests_gn_queue_idx ON requests(gn_id,status);
CREATE INDEX requests_ds_queue_idx ON requests(ds_id,status);
CREATE INDEX history_request_idx ON request_history(request_id,id);
CREATE INDEX documents_request_idx ON documents(request_id);
CREATE INDEX messages_request_idx ON messages(request_id,created_at,id);
CREATE INDEX notifications_user_idx ON notifications(user_id,id);
CREATE INDEX audit_actor_idx ON audit_logs(actor_id,id);
CREATE INDEX login_user_idx ON login_logs(user_id,id);
COMMIT;
