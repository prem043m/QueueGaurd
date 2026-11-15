CREATE TABLE organizations (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(160) NOT NULL UNIQUE,
  organization_type VARCHAR(60) NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE memberships (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  auth_user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_membership_org_user UNIQUE (organization_id, auth_user_id)
);
CREATE INDEX idx_memberships_user_status ON memberships(auth_user_id, status);
CREATE INDEX idx_memberships_org_status ON memberships(organization_id, status);
CREATE TABLE membership_roles (
  membership_id BIGINT NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
  role VARCHAR(30) NOT NULL,
  PRIMARY KEY (membership_id, role)
);
