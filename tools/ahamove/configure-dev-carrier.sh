#!/usr/bin/env bash
# Configures the AHAMOVE carrier of a running StockFlow server for Ahamove's STAGING API, through the server's own API
# (the same calls the carrier screen's API would make): the connection, the service codes per vehicle type, the pickup
# point of one warehouse. Read docs/business-design/ahamove-dev-runbook.md first.
#
# Secrets never appear on a command line, in a file or in the output: the three below come from the environment if it
# holds them, else they are asked for without echo; requests carry them on curl's stdin / a descriptor, not in argv.
#
#   STOCKFLOW_PASSWORD     the password of STOCKFLOW_USER (a warehouse manager or the system admin)
#   AHAMOVE_API_KEY        the partner API key Ahamove issued for staging
#   WEBHOOK_AUTH_VALUE     the value Ahamove is told to send in the callback header (16-512 characters; you choose it)
#
# Inputs (environment):
#   API_BASE               required: the server, e.g. https://api.<dev domain>
#   STOCKFLOW_USER         required: the sign-in name
#   AHAMOVE_API_ACCOUNT    required: the partner account (the registered phone number the API key belongs to)
#   PICKUP_CONTACT_PHONE   required: who the driver calls at the warehouse
#   CARRIER_CODE           default AHAMOVE
#   AHAMOVE_CITY           default HAN        Ahamove's city_id of the warehouse's city
#   WAREHOUSE_PREFIX       default HAN        the warehouse the pickup point is
#   PROVINCE_CODE          default 01         our province code of that city (orders to other provinces are refused)
#   PICKUP_ADDRESS         default: the warehouse's address
#   PICKUP_CONTACT_NAME    default: the warehouse's name
#   WEBHOOK_AUTH_HEADER    default apikey     apikey, or Authorization (then the value is "Bearer <token>" / "Basic <b64>")
#   SERVICE_MAP            vehicle type code = Ahamove service_id, comma separated, e.g. "TRUCK-1T25=HAN-XXXX,TRUCK-2T5=HAN-YYYY";
#                          left out, the script lists the city's services and asks, per vehicle type
#   VEHICLE_TYPES          default "PICKUP TRUCK-1T25 TRUCK-2T5"
#   FAILURE_MAP_JSON       default {"CUSTOMER_NOT_AVAILABLE":"CUSTOMER_ABSENT","CUSTOMER_REFUSED":"REFUSED","WRONG_ADDRESS":"WRONG_ADDRESS"}
#   DRY_RUN=1              read and print what would be written, write nothing
#
# Needs: bash, curl, jq. Safe to run again: it updates the carrier and merges the codes and the pickup point.
set -euo pipefail

: "${API_BASE:?set API_BASE, e.g. https://api.<dev domain>}"
: "${STOCKFLOW_USER:?set STOCKFLOW_USER}"
: "${AHAMOVE_API_ACCOUNT:?set AHAMOVE_API_ACCOUNT (the partner account / registered phone number)}"
: "${PICKUP_CONTACT_PHONE:?set PICKUP_CONTACT_PHONE}"
API_BASE=${API_BASE%/}
CARRIER_CODE=${CARRIER_CODE:-AHAMOVE}
AHAMOVE_CITY=${AHAMOVE_CITY:-HAN}
WAREHOUSE_PREFIX=${WAREHOUSE_PREFIX:-HAN}
PROVINCE_CODE=${PROVINCE_CODE:-01}
WEBHOOK_AUTH_HEADER=${WEBHOOK_AUTH_HEADER:-apikey}
VEHICLE_TYPES=${VEHICLE_TYPES:-PICKUP TRUCK-1T25 TRUCK-2T5}
FAILURE_MAP_JSON=${FAILURE_MAP_JSON:-'{"CUSTOMER_NOT_AVAILABLE":"CUSTOMER_ABSENT","CUSTOMER_REFUSED":"REFUSED","WRONG_ADDRESS":"WRONG_ADDRESS"}'}
DRY_RUN=${DRY_RUN:-0}

command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }

ask_secret() { # name, prompt - keeps what the environment holds, else reads without echo
  if [[ -z ${!1:-} ]]; then
    read -rsp "$2: " "$1" </dev/tty; echo >&2
  fi
  [[ -n ${!1:-} ]] || { echo "$1 is empty" >&2; exit 2; }
  export "${1?}"
}
ask_secret STOCKFLOW_PASSWORD "Password of $STOCKFLOW_USER"
ask_secret AHAMOVE_API_KEY "Ahamove staging API key"
ask_secret WEBHOOK_AUTH_VALUE "Callback credential Ahamove will send in the $WEBHOOK_AUTH_HEADER header (16-512 chars)"
(( ${#WEBHOOK_AUTH_VALUE} >= 16 && ${#WEBHOOK_AUTH_VALUE} <= 512 )) || { echo "WEBHOOK_AUTH_VALUE must be 16-512 characters" >&2; exit 2; }

TOKEN=""
# api METHOD PATH [body-from-stdin]: the token goes in a curl config on a descriptor, the body on stdin - never in argv.
api() {
  local method=$1 path=$2 out status
  out=$(mktemp)
  if [[ -t 0 || $method == GET ]]; then
    status=$(curl -sS -o "$out" -w '%{http_code}' -X "$method" -K <(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN") \
      "$API_BASE$path")
  else
    status=$(curl -sS -o "$out" -w '%{http_code}' -X "$method" -K <(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN") \
      -H 'Content-Type: application/json' --data-binary @- "$API_BASE$path")
  fi
  if [[ $status != 2* ]]; then
    echo "$method $path -> HTTP $status: $(jq -r '[.errorCode, .message] | map(select(. != null)) | join(" ")' "$out" 2>/dev/null)" >&2
    rm -f "$out"; exit 1
  fi
  cat "$out"; rm -f "$out"
}
write() { # METHOD PATH - body on stdin; nothing is sent under DRY_RUN
  if [[ $DRY_RUN == 1 ]]; then echo "[dry run] would $1 $2" >&2; cat >/dev/null; echo '{"data":null}'; else api "$1" "$2"; fi
}

echo "Signing in as $STOCKFLOW_USER ..." >&2
TOKEN=$(jq -n '{username: env.STOCKFLOW_USER, password: env.STOCKFLOW_PASSWORD}' |
  curl -sS -f -X POST -H 'Content-Type: application/json' --data-binary @- "$API_BASE/api/v1/identity/auth/login" |
  jq -r '.data.accessToken // empty')
[[ -n $TOKEN ]] || { echo "sign-in failed" >&2; exit 1; }
unset STOCKFLOW_PASSWORD

warehouse=$(api GET "/api/v1/warehouses?size=100" </dev/null | jq -c --arg p "$WAREHOUSE_PREFIX" '[.data.items[] | select(.prefix == $p)][0] // empty')
[[ -n $warehouse ]] || { echo "no warehouse with prefix $WAREHOUSE_PREFIX" >&2; exit 1; }
warehouse_id=$(jq -r .id <<<"$warehouse")

# ---- 1. the carrier and its connection
connection=$(AHAMOVE_ENV=${AHAMOVE_ENV:-STAGING} jq -n --argjson map "$FAILURE_MAP_JSON" --arg header "$WEBHOOK_AUTH_HEADER" '{
  connectionType: "API_BOOKING", webhookEnabled: true, webhookAuthHeader: $header,
  webhookAuthValue: env.WEBHOOK_AUTH_VALUE, apiEnvironment: env.AHAMOVE_ENV, apiAccount: env.AHAMOVE_API_ACCOUNT,
  apiKey: env.AHAMOVE_API_KEY, failureReasonMap: $map}')
existing=$(api GET "/api/v1/fulfillment/carriers?size=100" </dev/null | jq -c --arg c "$CARRIER_CODE" '[.data.items[] | select(.code == $c)][0] // empty')
if [[ -z $existing ]]; then
  echo "Creating carrier $CARRIER_CODE ..." >&2
  carrier_id=$(jq -n --arg code "$CARRIER_CODE" --argjson c "$connection" \
    '{code: $code, name: "Ahamove", contactName: "Ahamove Partner Support", status: "ACTIVE", connection: $c}' |
    write POST /api/v1/fulfillment/carriers | jq -r '.data.carrierId // "dry-run"')
else
  carrier_id=$(jq -r .carrierId <<<"$existing")
  echo "Updating carrier $CARRIER_CODE ..." >&2
  jq -n --argjson e "$existing" --argjson c "$connection" '{code: $e.code, name: $e.name, contactName: $e.contactName,
      contactPhone: $e.contactPhone, status: "ACTIVE", connection: $c, version: $e.version}' |
    write PUT "/api/v1/fulfillment/carriers/$carrier_id" >/dev/null
fi

# ---- 2. the real service ids of the city, and the service code of each vehicle type
if [[ $DRY_RUN == 1 && $carrier_id == dry-run ]]; then echo "[dry run] carrier not created: stopping here" >&2; exit 0; fi
services=$(api GET "/api/v1/fulfillment/carriers/$carrier_id/services?cityId=$AHAMOVE_CITY" </dev/null)
echo "Ahamove's truck services in $AHAMOVE_CITY (service_id  name):" >&2
jq -r '.data[] | "  \(.serviceCode)  \(.name)"' <<<"$services" >&2
vehicle_types=$(api GET "/api/v1/fulfillment/vehicle-types?size=100" </dev/null)
codes='[]'
for type in $VEHICLE_TYPES; do
  type_id=$(jq -r --arg t "$type" '[.data.items[] | select(.code == $t)][0].vehicleTypeId // empty' <<<"$vehicle_types")
  [[ -n $type_id ]] || { echo "no vehicle type $type" >&2; exit 1; }
  service=""
  if [[ -n ${SERVICE_MAP:-} ]]; then
    service=$(tr ',' '\n' <<<"$SERVICE_MAP" | awk -F= -v t="$type" '$1 == t {print $2}')
  else
    read -rp "service_id for $type (blank: none): " service </dev/tty
  fi
  [[ -z $service ]] && continue
  jq -e --arg s "$service" '.data[] | select(.serviceCode == $s)' <<<"$services" >/dev/null ||
    { echo "service_id $service is not among Ahamove's services for $AHAMOVE_CITY" >&2; exit 1; }
  codes=$(jq -c --arg t "$type_id" --arg p "$PROVINCE_CODE" --arg c "$AHAMOVE_CITY" --arg s "$service" \
    '. + [{vehicleTypeId: $t, provinceCode: $p, carrierCityCode: $c, serviceCode: $s}]' <<<"$codes")
done
[[ $codes != '[]' ]] || { echo "no service code given" >&2; exit 1; }
# merge: keep the codes of other provinces, replace those of this province
api GET "/api/v1/fulfillment/carriers/$carrier_id/service-codes" </dev/null |
  jq -c --argjson new "$codes" --arg p "$PROVINCE_CODE" '{codes: ([.data[] | select(.provinceCode != $p) |
      {vehicleTypeId, provinceCode, carrierCityCode, serviceCode}] + $new)}' |
  write PUT "/api/v1/fulfillment/carriers/$carrier_id/service-codes" >/dev/null

# ---- 3. the pickup point
api GET "/api/v1/fulfillment/carriers/$carrier_id/pickup-points" </dev/null |
  jq -c --argjson w "$warehouse" --arg wid "$warehouse_id" --arg p "$PROVINCE_CODE" \
    --arg address "${PICKUP_ADDRESS:-$(jq -r .address <<<"$warehouse")}" \
    --arg name "${PICKUP_CONTACT_NAME:-$(jq -r .name <<<"$warehouse")}" '{pickupPoints: ([.data[] |
      select(.warehouseId != $wid) | {warehouseId, provinceCode, address, contactName, contactPhone}] +
      [{warehouseId: $wid, provinceCode: $p, address: $address, contactName: $name,
        contactPhone: env.PICKUP_CONTACT_PHONE}])}' |
  write PUT "/api/v1/fulfillment/carriers/$carrier_id/pickup-points" >/dev/null

# ---- what to register at Ahamove (the credential itself is not printed)
echo >&2
api GET "/api/v1/fulfillment/carriers/$carrier_id" </dev/null |
  jq -r '.data | "Carrier \(.code): \(.connection.connectionType), \(.connection.apiEnvironment), webhook enabled=\(.connection.webhookEnabled), " +
         "api key set=\(.connection.apiKeyConfigured), callback credential set=\(.connection.webhookAuthValueConfigured)"' >&2
echo "Register at Ahamove:" >&2
echo "  callback URL : $API_BASE/api/v1/fulfillment/carrier-webhooks/$CARRIER_CODE" >&2
echo "  header       : $WEBHOOK_AUTH_HEADER (value: the WEBHOOK_AUTH_VALUE you entered)" >&2
