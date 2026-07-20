#!/bin/sh

# One-time Wear OS VPN authorization for Tailware.
# Works with the macOS/Linux Android SDK Platform-Tools `adb` binary.

set -eu

package_id='io.github.vladbezm.tailware'
serial=''
connect_address=''
adb_path=''
assume_yes='false'
dry_run='false'

usage() {
  cat <<'EOF'
Usage: ./tools/authorize-wear-vpn.sh [options]

Options:
  --serial SERIAL      Exact value shown by `adb devices -l`
  --connect HOST:PORT  Connect to the watch before authorization
  --package PACKAGE    Android package ID (default: io.github.vladbezm.tailware)
  --adb PATH           Path to the adb binary
  --yes                Skip the final confirmation prompt
  --dry-run            Show what would be authorized without changing the watch
  -h, --help           Show this help
EOF
}

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      [ "$#" -ge 2 ] || fail '--serial requires a value'
      serial=$2
      shift 2
      ;;
    --connect)
      [ "$#" -ge 2 ] || fail '--connect requires a value'
      connect_address=$2
      shift 2
      ;;
    --package)
      [ "$#" -ge 2 ] || fail '--package requires a value'
      package_id=$2
      shift 2
      ;;
    --adb)
      [ "$#" -ge 2 ] || fail '--adb requires a value'
      adb_path=$2
      shift 2
      ;;
    --yes)
      assume_yes='true'
      shift
      ;;
    --dry-run)
      dry_run='true'
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option '$1'"
      ;;
  esac
done

case "$package_id" in
  *[!A-Za-z0-9._]*|'') fail "invalid package ID '$package_id'" ;;
esac

if [ -z "$adb_path" ]; then
  if command -v adb >/dev/null 2>&1; then
    adb_path=$(command -v adb)
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    adb_path="$ANDROID_SDK_ROOT/platform-tools/adb"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    adb_path="$ANDROID_HOME/platform-tools/adb"
  else
    fail 'adb was not found. Install Android SDK Platform-Tools, add adb to PATH, or pass --adb.'
  fi
fi

[ -x "$adb_path" ] || fail "adb is not executable at '$adb_path'"

if [ -n "$connect_address" ]; then
  case "$connect_address" in
    *:*) ;;
    *) fail "invalid connect address '$connect_address'; expected HOST:PORT" ;;
  esac
  "$adb_path" connect "$connect_address"
  [ -n "$serial" ] || serial=$connect_address
fi

device_table=$("$adb_path" devices -l) || fail 'adb devices failed'

if [ -n "$serial" ]; then
  device_state=$(printf '%s\n' "$device_table" |
    awk -v wanted="$serial" '$1 == wanted { print $2; exit }')
  [ -n "$device_state" ] || fail "device '$serial' was not found by adb"
  [ "$device_state" = 'device' ] ||
    fail "device '$serial' is '$device_state', not ready"
else
  ready_serials=$(printf '%s\n' "$device_table" |
    awk 'NR > 1 && $2 == "device" { print $1 }')
  ready_count=$(printf '%s\n' "$ready_serials" |
    awk 'NF { count++ } END { print count + 0 }')
  [ "$ready_count" -gt 0 ] || fail 'no ready adb device was found'
  [ "$ready_count" -eq 1 ] ||
    fail "multiple ready devices were found; rerun with --serial SERIAL"
  serial=$(printf '%s\n' "$ready_serials" | awk 'NF { print; exit }')
fi

adb_device() {
  "$adb_path" -s "$serial" "$@"
}

sdk_level=$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')
case "$sdk_level" in
  ''|*[!0-9]*) fail "invalid Android API level '$sdk_level'" ;;
esac
[ "$sdk_level" -ge 30 ] ||
  fail "Android API $sdk_level is unsupported; Wear OS 3 / API 30 or newer is required"

manufacturer=$(adb_device shell getprop ro.product.manufacturer | tr -d '\r')
model=$(adb_device shell getprop ro.product.model | tr -d '\r')
features=$(adb_device shell pm list features | tr -d '\r')
if ! printf '%s\n' "$features" | grep -qx 'feature:android.hardware.type.watch'; then
  printf '%s\n' 'Warning: this device does not report android.hardware.type.watch.' >&2
fi

user_id=$(adb_device shell am get-current-user | tr -d '\r')
case "$user_id" in
  ''|*[!0-9]*) fail "could not determine the current Android user ('$user_id')" ;;
esac

package_path=$(adb_device shell pm path --user "$user_id" "$package_id" | tr -d '\r')
printf '%s\n' "$package_path" | grep -q '^package:' ||
  fail "package '$package_id' is not installed for Android user $user_id"

current_appop=$(adb_device shell appops get --user "$user_id" "$package_id" ACTIVATE_VPN |
  tr -d '\r')
always_on=$(adb_device shell settings --user "$user_id" get secure always_on_vpn_app |
  tr -d '\r')
[ "$always_on" != 'null' ] || always_on='(none)'

printf '\nTailware Wear VPN authorization\n'
printf '  Device       : %s %s\n' "$manufacturer" "$model"
printf '  Serial       : %s\n' "$serial"
printf '  Android API  : %s\n' "$sdk_level"
printf '  Android user : %s\n' "$user_id"
printf '  Package      : %s\n' "$package_id"
printf '  Current AppOp: %s\n' "$current_appop"
printf '  Always-on VPN: %s\n\n' "$always_on"

if [ "$dry_run" = 'true' ]; then
  printf 'Dry run: no device changes were made.\n'
  exit 0
fi

if [ "$assume_yes" != 'true' ]; then
  printf 'Grant ACTIVATE_VPN to Tailware on this device? [y/N] '
  read -r answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *)
      printf 'No device changes were made.\n'
      exit 0
      ;;
  esac
fi

adb_device shell appops set --user "$user_id" "$package_id" ACTIVATE_VPN allow
verified_appop=$(adb_device shell appops get --user "$user_id" "$package_id" ACTIVATE_VPN |
  tr -d '\r')

printf '%s\n' "$verified_appop" |
  grep -Eiq '(ACTIVATE_VPN|android:activate_vpn)[[:space:]]*:[[:space:]]*allow' ||
  fail "Android did not confirm VPN authorization: $verified_appop"

printf 'VPN authorization confirmed: %s\n' "$verified_appop"
printf 'Reopen Tailware. The setup screen will detect the authorization automatically.\n'
