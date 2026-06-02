#!/usr/bin/env bash
set -euo pipefail

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
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
token="$(printf '%s' "$login_response" | jq -r '.token // empty')"
if [[ -z "$token" ]]; then
  echo "Login succeeded but no token was returned." >&2
  exit 1
fi

echo "Importing watchlist from Trakt..."
start_ts="$(date +%s)"
import_result="$(request POST "$server/api/trakt/import-watchlist" "" "$token")"
import_status="$(printf '%s' "$import_result" | sed -n '1p')"
import_response="$(printf '%s' "$import_result" | sed '1d')"

if [[ ! "$import_status" =~ ^2[0-9][0-9]$ ]]; then
  echo "Import failed with HTTP $import_status:" >&2
  printf '%s\n' "$import_response" >&2
  if [[ "$import_status" == "409" ]]; then
    echo >&2
    echo "Link this Popcorn user to Trakt first with ./scripts/link-trakt.sh" >&2
  fi
  exit 1
fi

elapsed=$(( $(date +%s) - start_ts ))

echo
entries_seen="$(printf '%s\n' "$import_response" | jq -r '.entriesSeen // ((.moviesSeen // 0) + (.showsSeen // 0) + (.episodesSeen // 0))')"
if [[ "$entries_seen" == "0" ]]; then
  echo "Trakt returned an empty watchlist."
else
  echo "Trakt returned ${entries_seen} watchlist entries."
fi

printf '%s\n' "$import_response" | jq '{
  entriesSeen,
  moviesSeen,
  moviesMatched,
  moviesUnmatched,
  showsSeen,
  showsMatched,
  showsUnmatched,
  episodesSeen,
  episodesMatched,
  episodesUnmatched,
  itemsMarked,
  showsMarked,
  traktSources
}'
echo
echo "Import finished in ${elapsed}s."

matched_count="$(printf '%s\n' "$import_response" | jq '.matched // [] | length')"
if [[ "${POPCORN_VERBOSE_IMPORT:-0}" == "1" && "$matched_count" != "0" ]]; then
  echo
  echo "Matched watchlist entries:"
  printf '%s\n' "$import_response" | jq -r '.matched[]'
fi

unmatched_count="$(printf '%s\n' "$import_response" | jq '.unmatched // [] | length')"
if [[ "${POPCORN_VERBOSE_IMPORT:-0}" == "1" && "$unmatched_count" != "0" ]]; then
  echo
  echo "Unmatched watchlist entries:"
  printf '%s\n' "$import_response" | jq -r '.unmatched[]'
fi

if [[ "${POPCORN_VERBOSE_IMPORT:-0}" != "1" ]]; then
  echo "Set POPCORN_VERBOSE_IMPORT=1 to print matched and unmatched titles."
fi
