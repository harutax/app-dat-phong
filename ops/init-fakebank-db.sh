#!/bin/bash
# Tạo database + user RIÊNG cho Fake Bank (ranh giới ACID tách biệt với hệ thống đặt phòng).
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE USER fakebank WITH PASSWORD '${BANK_DB_PASSWORD:-fakebank}';
  CREATE DATABASE fakebank OWNER fakebank;
EOSQL
