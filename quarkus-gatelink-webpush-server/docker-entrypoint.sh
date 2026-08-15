#!/bin/sh
set -eu

TLS_DIR="${GATELINK_TLS_DIR:-/opt/app/tls}"
CERT_FILE="${TLS_DIR}/tls.crt"
KEY_FILE="${TLS_DIR}/tls.key"
COMMON_NAME="${GATELINK_TLS_COMMON_NAME:-quarkus-gatelink-webpush-server}"
SUBJECT_ALT_NAME="${GATELINK_TLS_SAN:-DNS:quarkus-gatelink-webpush-server,DNS:localhost,IP:127.0.0.1}"
DAYS="${GATELINK_TLS_DAYS:-825}"

mkdir -p "${TLS_DIR}"

if [ ! -s "${CERT_FILE}" ] || [ ! -s "${KEY_FILE}" ]; then
  echo "Generating self-signed Quarkus TLS certificate for ${COMMON_NAME}"
  rm -f "${CERT_FILE}" "${KEY_FILE}"
  umask 077
  openssl req \
    -x509 \
    -newkey rsa:3072 \
    -nodes \
    -sha256 \
    -days "${DAYS}" \
    -keyout "${KEY_FILE}" \
    -out "${CERT_FILE}" \
    -subj "/CN=${COMMON_NAME}" \
    -addext "subjectAltName=${SUBJECT_ALT_NAME}"
  chmod 600 "${KEY_FILE}"
  chmod 644 "${CERT_FILE}"
fi

exec java -jar /opt/app/app.jar
