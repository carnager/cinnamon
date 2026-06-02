#!/usr/bin/env bash
set -euo pipefail

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

json_get() {
  jq -r "$1 // empty"
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

request() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local token="${4:-}"
  local response_file status
  response_file="$(mktemp)"
  if [[ -n "$body" ]]; then
    status="$(curl -sS -o "$response_file" -w '%{http_code}' -X "$method" "$url" \
      -H 'Content-Type: application/json' \
      ${token:+-H "Authorization: Bearer $token"} \
      -d "$body")"
  else
    status="$(curl -sS -o "$response_file" -w '%{http_code}' -X "$method" "$url" \
      ${token:+-H "Authorization: Bearer $token"})"
  fi
  printf '%s\n' "$status"
  cat "$response_file"
  rm -f "$response_file"
}

need curl
need jq

default_server="${POPCORN_SERVER:-http://localhost:8097}"
read -r -p "Popcorn server [$default_server]: " server
server="$(trim "$server")"
server="${server:-$default_server}"
server="${server%/}"

read -r -p "Popcorn username: " username
read -r -s -p "Popcorn password: " password
echo

login_body="$(jq -n --arg username "$username" --arg password "$password" '{username:$username,password:$password}')"
login_result="$(request POST "$server/api/auth/login" "$login_body")"
login_status="$(printf '%s' "$login_result" | sed -n '1p')"
login_response="$(printf '%s' "$login_result" | sed '1d')"
if [[ ! "$login_status" =~ ^2[0-9][0-9]$ ]]; then
  echo "Login failed with HTTP $login_status:" >&2
  printf '%s\n' "$login_response" >&2
  exit 1
fi
token="$(printf '%s' "$login_response" | json_get '.token')"

if [[ -z "$token" ]]; then
  echo "Login succeeded but no token was returned." >&2
  exit 1
fi

device_result="$(request POST "$server/api/trakt/device" "" "$token")"
device_status="$(printf '%s' "$device_result" | sed -n '1p')"
device_response="$(printf '%s' "$device_result" | sed '1d')"
if [[ ! "$device_status" =~ ^2[0-9][0-9]$ ]]; then
  echo "Could not start Trakt device linking. HTTP $device_status:" >&2
  printf '%s\n' "$device_response" >&2
  if [[ "$device_status" == "503" ]]; then
    echo >&2
    echo "Restart popcornd so it picks up the built-in Trakt credentials." >&2
  fi
  exit 1
fi

device_code="$(printf '%s' "$device_response" | json_get '.device_code')"
user_code="$(printf '%s' "$device_response" | json_get '.user_code')"
verification_url="$(printf '%s' "$device_response" | json_get '.verification_url')"
expires_in="$(printf '%s' "$device_response" | json_get '.expires_in')"
interval="$(printf '%s' "$device_response" | json_get '.interval')"

if [[ -z "$device_code" || -z "$user_code" || -z "$verification_url" ]]; then
  echo "Trakt did not return a device link response:" >&2
  printf '%s\n' "$device_response" >&2
  exit 1
fi

interval="${interval:-5}"
expires_in="${expires_in:-600}"
deadline=$((SECONDS + expires_in))

echo
echo "Open this Trakt link:"
echo "$verification_url"
echo
echo "Enter code:"
echo "$user_code"
echo
echo "Waiting for approval. Press Ctrl-C to stop."

while (( SECONDS < deadline )); do
  token_body="$(jq -n --arg deviceCode "$device_code" '{deviceCode:$deviceCode}')"
  token_result="$(request POST "$server/api/trakt/device/token" "$token_body" "$token")"
  status="$(printf '%s' "$token_result" | sed -n '1p')"
  response="$(printf '%s' "$token_result" | sed '1d')"

  if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
    connected="$(printf '%s' "$response" | jq -r '.connected // false')"
    pending="$(printf '%s' "$response" | jq -r '.pending // false')"
    if [[ "$connected" == "true" ]]; then
      echo
      echo "Trakt linked for Popcorn user '$username'."
      exit 0
    fi
    if [[ "$pending" != "true" ]]; then
      echo "Unexpected response from server:" >&2
      printf '%s\n' "$response" >&2
      exit 1
    fi
  elif [[ "$status" == "409" ]]; then
    echo "Trakt says the code is already used. Start the script again for a fresh code." >&2
    exit 1
  elif [[ "$status" == "410" ]]; then
    echo "Trakt code expired. Start the script again for a fresh code." >&2
    exit 1
  else
    echo "Linking failed with HTTP $status:" >&2
    printf '%s\n' "$response" >&2
    exit 1
  fi

  sleep "$interval"
done

echo "Trakt code expired before approval." >&2
exit 1
