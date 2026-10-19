-- =====================================================================
-- HỆ THỐNG ĐẶT PHÒNG - schema khởi tạo (PostgreSQL 15+)
-- Nguyên tắc: constraint + index + transaction là tầng bảo đảm cuối cùng.
-- Mọi timestamp là timestamptz (UTC); stay_date/check_in/check_out là date theo múi giờ property.
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- identity
CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    email               VARCHAR(255) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    full_name           VARCHAR(150) NOT NULL,
    phone               VARCHAR(30),
    avatar_url          VARCHAR(500),
    status              VARCHAR(30)  NOT NULL,
    token_version       INTEGER      NOT NULL DEFAULT 0,
    verified_at         TIMESTAMPTZ,
    failed_login_count  INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    created_by_name     VARCHAR(100),
    updated_by_name     VARCHAR(100)
);
-- Email unique KHÔNG phân biệt hoa/thường, kể cả khi hai request đăng ký đồng thời.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(30) NOT NULL UNIQUE,
    description VARCHAR(200)
);
CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(60) NOT NULL UNIQUE,
    description VARCHAR(200)
);
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users (id),
    role_id BIGINT NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles (id),
    permission_id BIGINT NOT NULL REFERENCES permissions (id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE refresh_tokens (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id),
    token_hash       VARCHAR(64) NOT NULL UNIQUE,
    family_id        VARCHAR(36) NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ,
    replaced_by_hash VARCHAR(64),
    device           VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_family ON refresh_tokens (family_id);

CREATE TABLE verification_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    purpose    VARCHAR(30) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    attempts   INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_vtoken_user ON verification_tokens (user_id, purpose);

-- ---------------------------------------------------------------- property / catalog
CREATE TABLE amenities (
    id     BIGSERIAL PRIMARY KEY,
    code   VARCHAR(40)  NOT NULL UNIQUE,
    name   VARCHAR(100) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE properties (
    id                BIGSERIAL PRIMARY KEY,
    owner_id          BIGINT        NOT NULL REFERENCES users (id),
    name              VARCHAR(200)  NOT NULL,
    description       TEXT,
    property_type     VARCHAR(30)   NOT NULL DEFAULT 'HOTEL',
    address           VARCHAR(500),
    city              VARCHAR(100),
    latitude          NUMERIC(9, 6),
    longitude         NUMERIC(9, 6),
    timezone          VARCHAR(50)   NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    status            VARCHAR(20)   NOT NULL,
    check_in_time     TIME          NOT NULL DEFAULT '14:00',
    check_out_time    TIME          NOT NULL DEFAULT '12:00',
    free_cancel_hours INTEGER       NOT NULL DEFAULT 48 CHECK (free_cancel_hours >= 0),
    penalty_percent   INTEGER       NOT NULL DEFAULT 50 CHECK (penalty_percent BETWEEN 0 AND 100),
    contact_phone     VARCHAR(30),
    contact_email     VARCHAR(255),
    cover_media_id    BIGINT,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    created_by_name   VARCHAR(100),
    updated_by_name   VARCHAR(100)
);
CREATE INDEX idx_properties_owner ON properties (owner_id);
CREATE INDEX idx_properties_status_city ON properties (status, city);

CREATE TABLE property_amenities (
    property_id BIGINT NOT NULL REFERENCES properties (id),
    amenity_id  BIGINT NOT NULL REFERENCES amenities (id),
    PRIMARY KEY (property_id, amenity_id)
);

CREATE TABLE room_types (
    id              BIGSERIAL PRIMARY KEY,
    property_id     BIGINT       NOT NULL REFERENCES properties (id),
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    capacity        INTEGER      NOT NULL CHECK (capacity > 0),
    bed_type        VARCHAR(50),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by_name VARCHAR(100),
    updated_by_name VARCHAR(100)
);
CREATE INDEX idx_room_types_property ON room_types (property_id);

CREATE TABLE room_type_amenities (
    room_type_id BIGINT NOT NULL REFERENCES room_types (id),
    amenity_id   BIGINT NOT NULL REFERENCES amenities (id),
    PRIMARY KEY (room_type_id, amenity_id)
);

CREATE TABLE rooms (
    id              BIGSERIAL PRIMARY KEY,
    property_id     BIGINT      NOT NULL REFERENCES properties (id),
    room_type_id    BIGINT      NOT NULL REFERENCES room_types (id),
    room_code       VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by_name VARCHAR(100),
    updated_by_name VARCHAR(100),
    CONSTRAINT uq_room_code UNIQUE (property_id, room_code)
);
CREATE INDEX idx_rooms_type ON rooms (room_type_id, status);

CREATE TABLE room_rates (
    id              BIGSERIAL PRIMARY KEY,
    room_type_id    BIGINT        NOT NULL REFERENCES room_types (id),
    stay_date       DATE          NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL CHECK (amount >= 0),
    currency        VARCHAR(3)    NOT NULL DEFAULT 'VND',
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    created_by_name VARCHAR(100),
    updated_by_name VARCHAR(100),
    CONSTRAINT uq_rate_type_date UNIQUE (room_type_id, stay_date)
);

CREATE TABLE media_assets (
    id                BIGSERIAL PRIMARY KEY,
    owner_id          BIGINT       NOT NULL REFERENCES users (id),
    property_id       BIGINT       NOT NULL REFERENCES properties (id),
    room_type_id      BIGINT REFERENCES room_types (id),
    object_key        VARCHAR(300) NOT NULL UNIQUE,
    original_filename VARCHAR(255),
    content_type      VARCHAR(100),
    declared_size     BIGINT,
    size_bytes        BIGINT,
    checksum          VARCHAR(100),
    status            VARCHAR(20)  NOT NULL,
    sort_order        INTEGER      NOT NULL DEFAULT 0,
    cover             BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    created_by_name   VARCHAR(100),
    updated_by_name   VARCHAR(100)
);
CREATE INDEX idx_media_property ON media_assets (property_id, status);
CREATE INDEX idx_media_pending ON media_assets (status, expires_at);

-- ---------------------------------------------------------------- booking / inventory
CREATE TABLE bookings (
    id                   BIGSERIAL PRIMARY KEY,
    booking_code         VARCHAR(20)    NOT NULL UNIQUE,
    customer_id          BIGINT         NOT NULL REFERENCES users (id),
    property_id          BIGINT         NOT NULL REFERENCES properties (id),
    room_id              BIGINT         NOT NULL REFERENCES rooms (id),
    room_type_id         BIGINT         NOT NULL REFERENCES room_types (id),
    check_in             DATE           NOT NULL,
    check_out            DATE           NOT NULL,
    guest_count          INTEGER        NOT NULL CHECK (guest_count > 0),
    status               VARCHAR(30)    NOT NULL,
    expires_at           TIMESTAMPTZ,
    subtotal             NUMERIC(14, 2) NOT NULL CHECK (subtotal >= 0),
    tax_amount           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_amount         NUMERIC(14, 2) NOT NULL CHECK (total_amount >= 0),
    currency             VARCHAR(3)     NOT NULL DEFAULT 'VND',
    -- snapshot chính sách hủy tại thời điểm đặt
    free_cancel_deadline TIMESTAMPTZ    NOT NULL,
    penalty_percent      INTEGER        NOT NULL CHECK (penalty_percent BETWEEN 0 AND 100),
    cancelled_by         BIGINT,
    cancelled_at         TIMESTAMPTZ,
    cancel_reason        VARCHAR(500),
    cancellation_fee     NUMERIC(14, 2),
    refundable_amount    NUMERIC(14, 2),
    actual_check_in      TIMESTAMPTZ,
    actual_check_out     TIMESTAMPTZ,
    special_note         VARCHAR(1000),
    source_channel       VARCHAR(30)    NOT NULL DEFAULT 'WEB',
    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL,
    updated_at           TIMESTAMPTZ    NOT NULL,
    created_by_name      VARCHAR(100),
    updated_by_name      VARCHAR(100),
    CONSTRAINT ck_booking_dates CHECK (check_out > check_in)
);
CREATE INDEX idx_bookings_customer ON bookings (customer_id, created_at DESC);
CREATE INDEX idx_bookings_property_created ON bookings (property_id, created_at, status);
CREATE INDEX idx_bookings_property_checkin ON bookings (property_id, check_in);
CREATE INDEX idx_bookings_expiry ON bookings (expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_bookings_room ON bookings (room_id, check_in, check_out);

CREATE TABLE booking_nights (
    id           BIGSERIAL PRIMARY KEY,
    booking_id   BIGINT         NOT NULL REFERENCES bookings (id),
    stay_date    DATE           NOT NULL,
    nightly_rate NUMERIC(14, 2) NOT NULL CHECK (nightly_rate >= 0),
    CONSTRAINT uq_booking_night UNIQUE (booking_id, stay_date)
);
CREATE INDEX idx_booking_nights_date ON booking_nights (stay_date);

-- Mô hình tồn kho ĐƯỢC CHỌN: phòng vật lý (room_id) + exclusion constraint trên daterange của allocation đang active.
-- Quy tắc thống nhất: (1) khóa hàng room PESSIMISTIC_WRITE, (2) kiểm tra overlap, (3) insert allocation;
-- exclusion constraint là chốt chặn cuối. Giải phóng = active=false (đúng một lần, qua UPDATE có điều kiện).
CREATE TABLE room_allocations (
    id          BIGSERIAL PRIMARY KEY,
    room_id     BIGINT      NOT NULL REFERENCES rooms (id),
    booking_id  BIGINT      NOT NULL UNIQUE REFERENCES bookings (id),
    check_in    DATE        NOT NULL,
    check_out   DATE        NOT NULL,
    stay_range  DATERANGE GENERATED ALWAYS AS (daterange(check_in, check_out, '[)')) STORED,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    CONSTRAINT ck_alloc_dates CHECK (check_out > check_in),
    CONSTRAINT ex_room_no_overlap EXCLUDE USING gist (room_id WITH =, stay_range WITH &&) WHERE (active)
);
CREATE INDEX idx_alloc_room_active ON room_allocations (room_id, check_in) WHERE active;

CREATE TABLE booking_status_history (
    id             BIGSERIAL PRIMARY KEY,
    booking_id     BIGINT      NOT NULL REFERENCES bookings (id),
    from_status    VARCHAR(30),
    to_status      VARCHAR(30) NOT NULL,
    actor_id       BIGINT,
    actor_type     VARCHAR(20) NOT NULL,
    reason         VARCHAR(500),
    correlation_id VARCHAR(64),
    at             TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_bsh_booking ON booking_status_history (booking_id, at);

CREATE TABLE idempotency_records (
    id           BIGSERIAL PRIMARY KEY,
    scope        VARCHAR(100) NOT NULL,
    idem_key     VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64)  NOT NULL,
    result_ref   VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idem UNIQUE (scope, idem_key)
);

-- ---------------------------------------------------------------- payment / refund
CREATE TABLE payments (
    id                 BIGSERIAL PRIMARY KEY,
    intent_code        VARCHAR(40)    NOT NULL UNIQUE,
    booking_id         BIGINT         NOT NULL REFERENCES bookings (id),
    customer_id        BIGINT         NOT NULL REFERENCES users (id),
    idempotency_key    VARCHAR(100)   NOT NULL UNIQUE,
    amount             NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    currency           VARCHAR(3)     NOT NULL DEFAULT 'VND',
    status             VARCHAR(20)    NOT NULL,
    provider_txn_id    VARCHAR(100) UNIQUE,
    payer_account      VARCHAR(40),
    failure_code       VARCHAR(50),
    captured_at        TIMESTAMPTZ,
    last_checked_at    TIMESTAMPTZ,
    attempts           INTEGER        NOT NULL DEFAULT 0,
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    created_by_name    VARCHAR(100),
    updated_by_name    VARCHAR(100)
);
CREATE INDEX idx_payments_booking ON payments (booking_id, created_at DESC);
CREATE INDEX idx_payments_status ON payments (status, updated_at);
CREATE INDEX idx_payments_captured ON payments (status, captured_at);

CREATE TABLE payment_events (
    id                BIGSERIAL PRIMARY KEY,
    provider_event_id VARCHAR(100) NOT NULL UNIQUE,
    payment_id        BIGINT REFERENCES payments (id),
    event_type        VARCHAR(40)  NOT NULL,
    payload           TEXT,
    received_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE refunds (
    id              BIGSERIAL PRIMARY KEY,
    refund_key      VARCHAR(100)   NOT NULL UNIQUE,
    payment_id      BIGINT         NOT NULL REFERENCES payments (id),
    booking_id      BIGINT         NOT NULL REFERENCES bookings (id),
    amount          NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)    NOT NULL,
    reason          VARCHAR(300),
    provider_ref    VARCHAR(100) UNIQUE,
    failure_code    VARCHAR(50),
    attempts        INTEGER        NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    requested_by    BIGINT,
    succeeded_at    TIMESTAMPTZ,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    created_by_name VARCHAR(100),
    updated_by_name VARCHAR(100)
);
CREATE INDEX idx_refunds_payment ON refunds (payment_id, status);
CREATE INDEX idx_refunds_status ON refunds (status, next_attempt_at);
CREATE INDEX idx_refunds_succeeded ON refunds (status, succeeded_at);

CREATE TABLE reconciliation_cases (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    booking_id      BIGINT REFERENCES bookings (id),
    payment_id      BIGINT REFERENCES payments (id),
    refund_id       BIGINT REFERENCES refunds (id),
    details         TEXT,
    resolution_note VARCHAR(500),
    resolved_by     BIGINT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by_name VARCHAR(100),
    updated_by_name VARCHAR(100)
);
-- Mỗi payment chỉ một ca mở cho mỗi loại: callback lặp không sinh ca thứ hai.
CREATE UNIQUE INDEX uq_case_open ON reconciliation_cases (payment_id, type) WHERE status = 'OPEN';

-- ---------------------------------------------------------------- outbox / notification / audit
CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(80)  NOT NULL UNIQUE,
    event_type      VARCHAR(60)  NOT NULL,
    aggregate_type  VARCHAR(40)  NOT NULL,
    aggregate_id    VARCHAR(60)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_poll ON outbox_events (status, next_attempt_at);

CREATE TABLE notifications (
    id           BIGSERIAL PRIMARY KEY,
    event_id     VARCHAR(80)  NOT NULL,
    recipient_id BIGINT       NOT NULL REFERENCES users (id),
    type         VARCHAR(60)  NOT NULL,
    title        VARCHAR(200) NOT NULL,
    body         VARCHAR(1000),
    object_type  VARCHAR(40),
    object_id    VARCHAR(60),
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_notif_event_recipient UNIQUE (event_id, recipient_id)
);
CREATE INDEX idx_notif_recipient ON notifications (recipient_id, created_at DESC);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    BIGINT,
    actor_roles VARCHAR(100),
    action      VARCHAR(80)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   VARCHAR(60),
    request_id  VARCHAR(64),
    from_state  VARCHAR(60),
    to_state    VARCHAR(60),
    reason      VARCHAR(500),
    details     TEXT,
    outcome     VARCHAR(15)  NOT NULL DEFAULT 'SUCCESS',
    at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id, at);
CREATE INDEX idx_audit_actor ON audit_logs (actor_id, at);
CREATE INDEX idx_audit_request ON audit_logs (request_id);

-- ---------------------------------------------------------------- review
CREATE TABLE reviews (
    id               BIGSERIAL PRIMARY KEY,
    booking_id       BIGINT      NOT NULL UNIQUE REFERENCES bookings (id),
    property_id      BIGINT      NOT NULL REFERENCES properties (id),
    customer_id      BIGINT      NOT NULL REFERENCES users (id),
    rating           SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment          TEXT,
    status           VARCHAR(30) NOT NULL,
    host_reply       TEXT,
    host_replied_at  TIMESTAMPTZ,
    reported         BOOLEAN     NOT NULL DEFAULT FALSE,
    moderation_note  VARCHAR(500),
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    created_by_name  VARCHAR(100),
    updated_by_name  VARCHAR(100)
);
CREATE INDEX idx_reviews_property ON reviews (property_id, status);

-- ---------------------------------------------------------------- messaging
CREATE TABLE conversations (
    id               BIGSERIAL PRIMARY KEY,
    property_id      BIGINT      NOT NULL REFERENCES properties (id),
    booking_id       BIGINT REFERENCES bookings (id),
    customer_id      BIGINT      NOT NULL REFERENCES users (id),
    host_id          BIGINT      NOT NULL REFERENCES users (id),
    type             VARCHAR(30) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    last_message_seq BIGINT      NOT NULL DEFAULT 0,
    closed_at        TIMESTAMPTZ,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    created_by_name  VARCHAR(100),
    updated_by_name  VARCHAR(100)
);
-- Khóa nghiệp vụ duy nhất cho hội thoại đang mở: hai request khởi tạo song song không tạo trùng.
CREATE UNIQUE INDEX uq_conv_active ON conversations (customer_id, property_id, type, COALESCE(booking_id, 0)) WHERE status = 'OPEN';
CREATE INDEX idx_conv_host ON conversations (host_id, status);

CREATE TABLE conversation_participants (
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id),
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    role            VARCHAR(20) NOT NULL,
    last_read_seq   BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE messages (
    id                BIGSERIAL PRIMARY KEY,
    conversation_id   BIGINT      NOT NULL REFERENCES conversations (id),
    seq               BIGINT      NOT NULL,
    sender_id         BIGINT      NOT NULL REFERENCES users (id),
    client_message_id VARCHAR(64) NOT NULL,
    type              VARCHAR(20) NOT NULL,
    body              TEXT        NOT NULL,
    asset_id          BIGINT,
    body_hash         VARCHAR(64) NOT NULL,
    redacted          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_msg_seq UNIQUE (conversation_id, seq),
    CONSTRAINT uq_msg_client UNIQUE (conversation_id, sender_id, client_message_id)
);

CREATE TABLE complaint_tickets (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id),
    booking_id      BIGINT REFERENCES bookings (id),
    opened_by       BIGINT      NOT NULL REFERENCES users (id),
    category        VARCHAR(40) NOT NULL,
    severity        VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    deadline_at     TIMESTAMPTZ,
    handler_id      BIGINT REFERENCES users (id),
    resolution_note VARCHAR(1000),
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by_name VARCHAR(100),
    updated_by_name VARCHAR(100)
);
CREATE INDEX idx_ticket_conv ON complaint_tickets (conversation_id);

CREATE TABLE checkin_requests (
    id               BIGSERIAL PRIMARY KEY,
    booking_id       BIGINT      NOT NULL REFERENCES bookings (id),
    conversation_id  BIGINT REFERENCES conversations (id),
    proposed_at      TIMESTAMPTZ NOT NULL,
    agreed_checkin_at TIMESTAMPTZ,
    note             VARCHAR(500),
    status           VARCHAR(30) NOT NULL,
    responded_by     BIGINT,
    response_note    VARCHAR(500),
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    created_by_name  VARCHAR(100),
    updated_by_name  VARCHAR(100)
);
CREATE INDEX idx_checkin_req_booking ON checkin_requests (booking_id, status);

-- ---------------------------------------------------------------- config / email
CREATE TABLE config_definitions (
    id          BIGSERIAL PRIMARY KEY,
    cfg_key     VARCHAR(100) NOT NULL UNIQUE,
    value_type  VARCHAR(20)  NOT NULL,
    min_value   NUMERIC(14, 2),
    max_value   NUMERIC(14, 2),
    default_value VARCHAR(500) NOT NULL,
    scope       VARCHAR(20)  NOT NULL DEFAULT 'GLOBAL',
    public_flag BOOLEAN      NOT NULL DEFAULT FALSE,
    description VARCHAR(300)
);
CREATE TABLE config_values (
    id           BIGSERIAL PRIMARY KEY,
    cfg_key      VARCHAR(100) NOT NULL,
    scope        VARCHAR(20)  NOT NULL DEFAULT 'GLOBAL',
    scope_id     VARCHAR(50)  NOT NULL DEFAULT '',
    cfg_value    VARCHAR(500) NOT NULL,
    effective_at TIMESTAMPTZ  NOT NULL,
    version      INTEGER      NOT NULL DEFAULT 1,
    updated_by   BIGINT,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_cfg UNIQUE (cfg_key, scope, scope_id)
);
CREATE TABLE config_change_logs (
    id         BIGSERIAL PRIMARY KEY,
    cfg_key    VARCHAR(100) NOT NULL,
    old_value  VARCHAR(500),
    new_value  VARCHAR(500) NOT NULL,
    version    INTEGER      NOT NULL,
    changed_by BIGINT,
    reason     VARCHAR(300),
    at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE email_templates (
    id             BIGSERIAL PRIMARY KEY,
    template_code  VARCHAR(60) NOT NULL,
    locale         VARCHAR(10) NOT NULL DEFAULT 'vi',
    active_version INTEGER,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_template UNIQUE (template_code, locale)
);
CREATE TABLE email_template_versions (
    id                BIGSERIAL PRIMARY KEY,
    template_id       BIGINT       NOT NULL REFERENCES email_templates (id),
    version           INTEGER      NOT NULL,
    subject_template  VARCHAR(300) NOT NULL,
    html_body         TEXT         NOT NULL,
    text_body         TEXT         NOT NULL,
    allowed_variables VARCHAR(500) NOT NULL,
    status            VARCHAR(15)  NOT NULL,
    published_at      TIMESTAMPTZ,
    created_by        BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_template_version UNIQUE (template_id, version)
);

CREATE TABLE mail_events (
    id                  BIGSERIAL PRIMARY KEY,
    dedupe_key          VARCHAR(250) NOT NULL UNIQUE,
    event_type          VARCHAR(40)  NOT NULL,
    business_id         VARCHAR(60),
    booking_id          BIGINT,
    recipient           VARCHAR(255) NOT NULL,
    template_version_id BIGINT REFERENCES email_template_versions (id),
    payload_snapshot    TEXT         NOT NULL,
    status              VARCHAR(25)  NOT NULL,
    attempt_count       INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ  NOT NULL,
    lease_until         TIMESTAMPTZ,
    provider_message_id VARCHAR(200),
    error_code          VARCHAR(80),
    due_at              TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_mail_poll ON mail_events (status, next_attempt_at);
CREATE INDEX idx_mail_booking ON mail_events (booking_id);

CREATE TABLE mail_delivery_attempts (
    id                  BIGSERIAL PRIMARY KEY,
    mail_event_id       BIGINT      NOT NULL REFERENCES mail_events (id),
    attempt_no          INTEGER     NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    outcome             VARCHAR(20) NOT NULL,
    error_code          VARCHAR(80),
    provider_message_id VARCHAR(200),
    operator_id         BIGINT,
    operator_idem_key   VARCHAR(100)
);
CREATE UNIQUE INDEX uq_attempt_operator ON mail_delivery_attempts (mail_event_id, operator_idem_key) WHERE operator_idem_key IS NOT NULL;

CREATE TABLE scheduled_reminders (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT      NOT NULL REFERENCES bookings (id),
    reminder_type   VARCHAR(30) NOT NULL,
    window_code     VARCHAR(30) NOT NULL,
    policy_version  INTEGER     NOT NULL,
    due_at          TIMESTAMPTZ NOT NULL,
    status          VARCHAR(15) NOT NULL,
    mail_event_id   BIGINT REFERENCES mail_events (id),
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_reminder UNIQUE (booking_id, reminder_type, window_code, policy_version)
);
CREATE INDEX idx_reminder_due ON scheduled_reminders (status, due_at);

-- ---------------------------------------------------------------- analytics event log (append-only)
CREATE TABLE business_events (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(80)  NOT NULL UNIQUE,
    event_type  VARCHAR(40)  NOT NULL,
    property_id BIGINT,
    session_key VARCHAR(80),
    ref_id      VARCHAR(60),
    code        VARCHAR(60),
    event_time  TIMESTAMPTZ  NOT NULL,
    ingested_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_bevents_time ON business_events (property_id, event_time, event_type);
