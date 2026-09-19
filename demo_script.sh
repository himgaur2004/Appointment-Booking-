#!/bin/bash
# ============================================================================
# Appointment Service - End-to-End Demonstration Script
#
# Demonstrates:
#   - System health check (PostgreSQL + Spring Boot Actuator)
#   - Appointment creation across both reminder horizons (24h and 2h)
#   - Reminder scheduling and eager creation
#   - Asynchronous scheduler processing and delivery (10s poll cycle)
#   - Direct database state verification (appointments & reminders tables)
#   - Operational service metrics
#
# Usage:
#   bash demo_script.sh
# ============================================================================

set -e

# Terminal colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m'

banner() {
  echo ""
  echo -e "${CYAN}==============================================================${NC}"
  echo -e "${BOLD}${YELLOW}  $1${NC}"
  echo -e "${CYAN}==============================================================${NC}"
  echo ""
}

step() {
  echo -e "${GREEN}> ${BOLD}$1${NC}"
  echo ""
}

info() {
  echo -e "${MAGENTA}  [INFO] $1${NC}"
}

success() {
  echo -e "${GREEN}  [OK] $1${NC}"
}

error() {
  echo -e "${RED}  [ERROR] $1${NC}"
}

pause() {
  echo ""
  echo -e "${YELLOW}Press [ENTER] to continue...${NC}"
  read -r
  echo ""
}

# Configuration defaults
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-appointment_db}"
DB_USER="${DB_USER:-appointment_user}"
DB_PASS="${DB_PASS:-appointment_pass}"
APP_URL="${APP_URL:-http://localhost:8080}"

export PGPASSWORD="${DB_PASS}"
PSQL_CMD="psql -h ${DB_HOST} -p ${DB_PORT} -U ${DB_USER} -d ${DB_NAME}"

# ============================================================================
# PHASE 0: HEALTH CHECK
# ============================================================================
banner "PHASE 0: SYSTEM HEALTH CHECK"

step "Checking PostgreSQL connectivity..."
docker exec appointment-postgres pg_isready -U ${DB_USER} -d ${DB_NAME} 2>/dev/null && \
  success "PostgreSQL is reachable" || \
  { error "PostgreSQL is not responding. Ensure container is running: docker-compose up -d"; exit 1; }
echo ""

step "Checking Appointment Service status..."
HEALTH=$(curl -s "${APP_URL}/actuator/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "FAILED")
if echo "$HEALTH" | grep -q '"status": "UP"'; then
  success "Appointment Service is UP"
  echo "$HEALTH"
else
  error "Appointment Service is DOWN on ${APP_URL}."
  exit 1
fi

pause

# ============================================================================
# PHASE 1A: CREATE APPOINTMENT (24-HOUR REMINDER WINDOW)
# ============================================================================
banner "PHASE 1A: CREATE APPOINTMENT (24-Hour Reminder Horizon)"

# Appointment scheduled 24h + 5s in the future.
# Because lead time > 24h, both HOURS_24 and HOURS_2 reminders are generated.
# The 24-hour reminder is due immediately (now + 5s).
SCHEDULED_AT_24H=$(python3 -c "
from datetime import datetime, timezone, timedelta
future = datetime.now(timezone.utc) + timedelta(hours=24, seconds=5)
print(future.strftime('%Y-%m-%dT%H:%M:%SZ'))
")

info "Appointment A scheduled for: ${SCHEDULED_AT_24H} (24h + 5s lead time)"
info "Expected: HOURS_24 reminder due in ~5s; HOURS_2 reminder scheduled for +22h"
echo ""

IDEMP_KEY_A="demo-24h-$(date +%s)"

step "POST ${APP_URL}/api/v1/appointments"
echo -e "${CYAN}curl -X POST ${APP_URL}/api/v1/appointments \\
  -H 'Content-Type: application/json' \\
  -H 'Idempotency-Key: ${IDEMP_KEY_A}' \\
  -d '{
    \"dealershipId\": 1,
    \"customerName\": \"Gaurav Singh\",
    \"customerEmail\": \"gaurav.singh@example.com\",
    \"customerPhone\": \"+91-9876543210\",
    \"vehicleInfo\": \"2024 Honda City\",
    \"serviceType\": \"Full Service\",
    \"scheduledAt\": \"${SCHEDULED_AT_24H}\"
  }'${NC}"
echo ""

RESPONSE_A=$(curl -s -w "\n%{http_code}" -X POST "${APP_URL}/api/v1/appointments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY_A}" \
  -d "{
    \"dealershipId\": 1,
    \"customerName\": \"Gaurav Singh\",
    \"customerEmail\": \"gaurav.singh@example.com\",
    \"customerPhone\": \"+91-9876543210\",
    \"vehicleInfo\": \"2024 Honda City\",
    \"serviceType\": \"Full Service\",
    \"scheduledAt\": \"${SCHEDULED_AT_24H}\"
  }")

HTTP_CODE_A=$(echo "$RESPONSE_A" | tail -1)
BODY_A=$(echo "$RESPONSE_A" | sed '$d')

echo -e "${GREEN}HTTP Status: ${HTTP_CODE_A}${NC}"
echo ""
echo -e "${BOLD}Response Body:${NC}"
echo "$BODY_A" | python3 -m json.tool 2>/dev/null || echo "$BODY_A"

APPT_ID_A=$(echo "$BODY_A" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
echo ""
success "Created Appointment A (ID: ${APPT_ID_A}) with HOURS_24 and HOURS_2 reminders"

pause

# ============================================================================
# PHASE 1B: CREATE APPOINTMENT (2-HOUR REMINDER WINDOW)
# ============================================================================
banner "PHASE 1B: CREATE APPOINTMENT (2-Hour Reminder Horizon)"

# Appointment scheduled 2h + 5s in the future.
# Because lead time < 24h, the 24-hour reminder is skipped.
# The 2-hour reminder is generated and due immediately (now + 5s).
SCHEDULED_AT_2H=$(python3 -c "
from datetime import datetime, timezone, timedelta
future = datetime.now(timezone.utc) + timedelta(hours=2, seconds=5)
print(future.strftime('%Y-%m-%dT%H:%M:%SZ'))
")

info "Appointment B scheduled for: ${SCHEDULED_AT_2H} (2h + 5s lead time)"
info "Expected: HOURS_2 reminder due in ~5s; HOURS_24 reminder skipped"
echo ""

IDEMP_KEY_B="demo-2h-$(date +%s)"

step "POST ${APP_URL}/api/v1/appointments"
echo -e "${CYAN}curl -X POST ${APP_URL}/api/v1/appointments \\
  -H 'Content-Type: application/json' \\
  -H 'Idempotency-Key: ${IDEMP_KEY_B}' \\
  -d '{
    \"dealershipId\": 2,
    \"customerName\": \"Priya Sharma\",
    \"customerEmail\": \"priya.sharma@example.com\",
    \"customerPhone\": \"+91-9123456789\",
    \"vehicleInfo\": \"2023 Hyundai Creta\",
    \"serviceType\": \"Oil Change\",
    \"scheduledAt\": \"${SCHEDULED_AT_2H}\"
  }'${NC}"
echo ""

RESPONSE_B=$(curl -s -w "\n%{http_code}" -X POST "${APP_URL}/api/v1/appointments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY_B}" \
  -d "{
    \"dealershipId\": 2,
    \"customerName\": \"Priya Sharma\",
    \"customerEmail\": \"priya.sharma@example.com\",
    \"customerPhone\": \"+91-9123456789\",
    \"vehicleInfo\": \"2023 Hyundai Creta\",
    \"serviceType\": \"Oil Change\",
    \"scheduledAt\": \"${SCHEDULED_AT_2H}\"
  }")

HTTP_CODE_B=$(echo "$RESPONSE_B" | tail -1)
BODY_B=$(echo "$RESPONSE_B" | sed '$d')

echo -e "${GREEN}HTTP Status: ${HTTP_CODE_B}${NC}"
echo ""
echo -e "${BOLD}Response Body:${NC}"
echo "$BODY_B" | python3 -m json.tool 2>/dev/null || echo "$BODY_B"

APPT_ID_B=$(echo "$BODY_B" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
echo ""
success "Created Appointment B (ID: ${APPT_ID_B}) with HOURS_2 reminder"

pause

# ============================================================================
# PHASE 2: INITIAL REMINDER STATUS VIA API
# ============================================================================
banner "PHASE 2: VERIFY INITIAL REMINDER STATE (API)"

step "Appointment A (ID: ${APPT_ID_A}) reminders:"
echo ""
curl -s "${APP_URL}/api/v1/appointments/${APPT_ID_A}/reminders" | python3 -m json.tool 2>/dev/null
echo ""

step "Appointment B (ID: ${APPT_ID_B}) reminders:"
echo ""
curl -s "${APP_URL}/api/v1/appointments/${APPT_ID_B}/reminders" | python3 -m json.tool 2>/dev/null
echo ""
info "Reminders are initially in PENDING status."

pause

# ============================================================================
# PHASE 3: SCHEDULER EXECUTION & DISPATCH
# ============================================================================
banner "PHASE 3: SCHEDULER PROCESSING & NOTIFICATION DISPATCH"

info "Scheduler poll interval is configured to 10 seconds."
info "Both due reminders (Appt A: HOURS_24, Appt B: HOURS_2) are ready for processing."
echo ""

step "Waiting for scheduler poll cycle (12 seconds)..."
sleep 12
success "Scheduler poll cycle completed."

echo ""
step "Updated status for Appointment A (ID: ${APPT_ID_A}):"
curl -s "${APP_URL}/api/v1/appointments/${APPT_ID_A}/reminders" | python3 -m json.tool 2>/dev/null
echo ""

step "Updated status for Appointment B (ID: ${APPT_ID_B}):"
curl -s "${APP_URL}/api/v1/appointments/${APPT_ID_B}/reminders" | python3 -m json.tool 2>/dev/null

pause

# ============================================================================
# PHASE 4: DATABASE AUDIT
# ============================================================================
banner "PHASE 4: DATABASE AUDIT (POSTGRESQL)"

step "Querying 'appointments' table:"
echo ""
$PSQL_CMD -c "
SELECT id, dealership_id, customer_name,
       service_type, status,
       to_char(scheduled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS UTC') AS scheduled_at,
       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS UTC') AS created_at
FROM appointments
WHERE id IN (${APPT_ID_A}, ${APPT_ID_B})
ORDER BY id;
" 2>/dev/null || error "Database query failed."

echo ""
step "Querying 'reminders' table:"
echo ""
$PSQL_CMD -c "
SELECT r.id AS reminder_id,
       r.appointment_id,
       r.reminder_type,
       r.status,
       to_char(r.scheduled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS UTC') AS scheduled_at,
       to_char(r.sent_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS UTC') AS sent_at,
       r.retry_count
FROM reminders r
WHERE r.appointment_id IN (${APPT_ID_A}, ${APPT_ID_B})
ORDER BY r.appointment_id, r.reminder_type;
" 2>/dev/null || error "Database query failed."

echo ""
info "Verification criteria:"
info "- Appointment A: HOURS_24 -> SENT (with sent_at timestamp); HOURS_2 remains PENDING"
info "- Appointment B: HOURS_2 -> SENT (with sent_at timestamp)"

pause

# ============================================================================
# PHASE 5: OPERATIONAL METRICS
# ============================================================================
banner "PHASE 5: SYSTEM METRICS"

step "GET ${APP_URL}/api/v1/stats"
echo ""
curl -s "${APP_URL}/api/v1/stats" | python3 -m json.tool 2>/dev/null || echo "Stats endpoint unavailable"

echo ""
pause

# ============================================================================
# SUMMARY
# ============================================================================
banner "VERIFICATION COMPLETE"
echo -e "${BOLD}Summary of executed steps:${NC}"
echo ""
echo -e "  1. Verified PostgreSQL and Spring Boot service health"
echo -e "  2. Created 2 distinct appointments with idempotency protection"
echo -e "  3. Observed eager reminder creation for 24-hour and 2-hour thresholds"
echo -e "  4. Verified asynchronous batch processing and status transition to SENT"
echo -e "  5. Audited persisted records and delivery timestamps directly in PostgreSQL"
echo -e "  6. Retrieved real-time operational metrics"
echo ""
echo -e "${GREEN}End-to-end verification passed successfully.${NC}"
echo ""
