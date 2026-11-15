-- QueueGuard: one logical database per microservice (database-per-service pattern).
-- No service ever touches another service's tables.
CREATE DATABASE auth_db;
CREATE DATABASE queue_db;
CREATE DATABASE worker_db;
CREATE DATABASE ratelimit_db;
CREATE DATABASE monitor_db;
CREATE DATABASE gateway_db;
CREATE DATABASE qms_db;
