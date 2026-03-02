CREATE TABLE IF NOT EXISTS registration_verification_codes (
  id VARCHAR(36) PRIMARY KEY,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  code_hash VARCHAR(128) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  consumed_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reg_verification_email_active
  ON registration_verification_codes(email, consumed_at, expires_at);

CREATE INDEX IF NOT EXISTS idx_reg_verification_expires_at
  ON registration_verification_codes(expires_at);
