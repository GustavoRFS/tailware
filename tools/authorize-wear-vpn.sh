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
enable_always_on='false'
revoke='false'
allow_non_wear='false'
platform_tools_url='https://developer.android.com/tools/releases/platform-tools'

usage() {
  cat <<'EOF'
Usage: ./tools/authorize-wear-vpn.sh [options]

Options:
  --serial SERIAL      Exact value shown by `adb devices -l`
  --connect HOST:PORT  Connect to the watch before authorization
  --package PACKAGE    Android package ID (default: io.github.vladbezm.tailware)
  --adb PATH           Path to the adb binary
  --enable-always-on   Also configure Tailware as always-on, without lockdown
  --revoke             Restore ACTIVATE_VPN to default; remove always-on only
                       when Tailware is the current owner
  --allow-non-wear     Explicitly allow an emulator/device without Wear feature
  --yes                Skip the final confirmation prompt (automation only)
  --dry-run            Show what would be authorized without changing the watch
  -h, --help           Show this help
EOF
}

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

validate_connect_address() {
  address_host=${connect_address%:*}
  address_port=${connect_address##*:}
  [ "$address_host" != "$connect_address" ] &&
    [ -n "$address_host" ] &&
    [ -n "$address_port" ] ||
    fail "invalid ADB connection address '$connect_address'; expected WATCH-IP:DEBUG-PORT"

  case "$address_port" in
    ''|*[!0-9]*)
      fail "invalid ADB connection address '$connect_address'; the debugging port must be a number"
      ;;
  esac
  [ "${#address_port}" -le 5 ] ||
    fail "invalid ADB connection address '$connect_address'; the debugging port must be between 1 and 65535"
  [ "$address_port" -ge 1 ] && [ "$address_port" -le 65535 ] ||
    fail "invalid ADB connection address '$connect_address'; the debugging port must be between 1 and 65535"
  case "$address_host" in
    *[[:space:]]*)
      fail "invalid ADB connection address '$connect_address'; the host cannot contain whitespace"
      ;;
  esac
  if ! printf '%s\n' "$address_host" |
    grep -Eq '^([A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+(%[A-Za-z0-9._-]+)?\])$'; then
    fail "invalid ADB connection address '$connect_address'; use HOST:PORT, IPv4:PORT, or [IPv6]:PORT from the main Wireless debugging screen"
  fi
}

validate_serial() {
  case "$serial" in
    ''|-*)
      fail "invalid ADB serial '$serial'; copy the exact serial shown by 'adb devices -l'"
      ;;
  esac
  if printf '%s\n' "$serial" | grep -Eq '^[A-Za-z0-9._:%-]+$'; then
    return 0
  fi
  if printf '%s\n' "$serial" |
    grep -Eq '^\[[0-9A-Fa-f:.]+(%[A-Za-z0-9._-]+)?\]:[0-9]+$'; then
    return 0
  fi
  fail "invalid ADB serial '$serial'; Tailware accepts the normal USB, emulator, mDNS, IPv4, and bracketed-IPv6 serial characters only"
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
    --enable-always-on)
      enable_always_on='true'
      shift
      ;;
    --revoke)
      revoke='true'
      shift
      ;;
    --allow-non-wear)
      allow_non_wear='true'
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

[ "$enable_always_on" != 'true' ] || [ "$revoke" != 'true' ] ||
  fail '--enable-always-on and --revoke cannot be used together'

if ! printf '%s\n' "$package_id" |
  grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'; then
  fail "invalid package ID '$package_id'"
fi

if [ -n "$serial" ]; then
  validate_serial
fi
if [ -n "$connect_address" ]; then
  validate_connect_address
fi
if [ -n "$connect_address" ] &&
  [ -n "$serial" ] &&
  [ "$connect_address" != "$serial" ]; then
  fail "refusing ambiguous targets: --connect '$connect_address' and --serial '$serial' identify different devices; use only one selector, or pass the same exact address to both"
fi

if [ -z "$adb_path" ]; then
  if command -v adb >/dev/null 2>&1; then
    adb_path=$(command -v adb)
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    adb_path="$ANDROID_SDK_ROOT/platform-tools/adb"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    adb_path="$ANDROID_HOME/platform-tools/adb"
  else
    fail "adb was not found. Download Android SDK Platform-Tools from $platform_tools_url, extract it, then add adb to PATH or pass --adb /path/to/platform-tools/adb."
  fi
fi

[ -x "$adb_path" ] || fail "adb is not executable at '$adb_path'"

adb_version_output=$("$adb_path" version 2>&1) ||
  fail "could not run '$adb_path version'. Install the current official Platform-Tools from $platform_tools_url"
adb_version=$(printf '%s\n' "$adb_version_output" |
  tr -d '\r' |
  awk '$1 == "Version" && $2 ~ /^[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9._-]+)?$/ { print $2; exit }')
[ -n "$adb_version" ] ||
  fail "could not determine the Platform-Tools version from 'adb version'. Install the current official release from $platform_tools_url"
adb_major=${adb_version%%.*}
[ "$adb_major" -ge 30 ] ||
  fail "Android SDK Platform-Tools $adb_version is too old. Tailware requires version 30 or newer. Download the current official release from $platform_tools_url"

if [ -n "$connect_address" ]; then
  connect_output=$("$adb_path" connect "$connect_address" 2>&1) ||
    fail "adb could not connect to '$connect_address'. Make sure Wireless debugging is on and use the debugging port from its main screen, not the temporary pairing port.
$connect_output"
  if ! printf '%s\n' "$connect_output" |
    tr -d '\r' |
    grep -Eq '^[[:space:]]*(already )?connected to[[:space:]]+[^[:space:]]+[[:space:]]*$'; then
    fail "adb could not connect to '$connect_address'. Make sure Wireless debugging is on and use the debugging port from its main screen, not the temporary pairing port.
$connect_output"
  fi
  printf 'ADB connect: %s\n' "$connect_output"
  [ -n "$serial" ] || serial=$connect_address
fi

device_table=$("$adb_path" devices -l) ||
  fail "adb could not list devices. Restart Platform-Tools and confirm Wireless debugging is enabled on the watch."

if [ -n "$serial" ]; then
  selected_states=$(printf '%s\n' "$device_table" |
    awk -v wanted="$serial" 'NR > 1 && $1 == wanted { print $2 }')
  selected_count=$(printf '%s\n' "$selected_states" |
    awk 'NF { count++ } END { print count + 0 }')
  [ "$selected_count" -gt 0 ] ||
    fail "adb cannot find '$serial'. Pair the watch first, then run 'adb connect WATCH-IP:DEBUG-PORT'. The pairing port and debugging port are different."
  [ "$selected_count" -eq 1 ] ||
    fail "adb reported the requested serial '$serial' more than once. Refusing an ambiguous target; restart the ADB server and reconnect only the intended watch."
  device_state=$(printf '%s\n' "$selected_states" | awk 'NF { print; exit }')
  case "$device_state" in
    device) ;;
    unauthorized)
      fail "adb found '$serial', but the watch has not authorized this computer. Unlock the watch and accept its Allow debugging prompt. If no prompt appears, turn Wireless debugging off and on, then pair again."
      ;;
    offline)
      fail "adb found '$serial', but it is offline. Turn Wireless debugging off and on, then run adb connect again with the current debugging port."
      ;;
    no|"no permissions")
      fail "this computer cannot access '$serial'. Reconnect it, accept the watch prompt, and check the computer ADB permissions."
      ;;
    *)
      fail "adb found '$serial', but its state is '$device_state' instead of ready. Unlock the watch, confirm Wireless debugging is on, and reconnect."
      ;;
  esac
else
  visible_serials=$(printf '%s\n' "$device_table" |
    awk 'NR > 1 && NF >= 2 && $1 !~ /^\*/ { print $1 " (" $2 ")" }')
  visible_count=$(printf '%s\n' "$visible_serials" |
    awk 'NF { count++ } END { print count + 0 }')
  if [ "$visible_count" -gt 1 ]; then
    fail "more than one ADB device is visible:
$visible_serials
To avoid changing the wrong device, rerun with --serial SERIAL or --connect WATCH-IP:DEBUG-PORT. This guard includes offline and unauthorized entries, not only ready devices."
  fi
  ready_serials=$(printf '%s\n' "$device_table" |
    awk 'NR > 1 && $2 == "device" { print $1 }')
  ready_count=$(printf '%s\n' "$ready_serials" |
    awk 'NF { count++ } END { print count + 0 }')
  if [ "$ready_count" -eq 0 ]; then
    device_summary=$(printf '%s\n' "$device_table" |
      awk 'NR > 1 && NF >= 2 { print "  " $1 " (" $2 ")" }')
    [ -n "$device_summary" ] || device_summary='  (none)'
    if printf '%s\n' "$device_table" | awk 'NR > 1 && $2 == "unauthorized" { found=1 } END { exit !found }'; then
      fail "adb found the watch, but it is unauthorized. Unlock it and accept the Allow debugging prompt. If no prompt appears, turn Wireless debugging off and on, then pair again.
Devices:
$device_summary"
    fi
    if printf '%s\n' "$device_table" | awk 'NR > 1 && $2 == "offline" { found=1 } END { exit !found }'; then
      fail "adb found the watch, but it is offline. Turn Wireless debugging off and on, then run 'adb connect WATCH-IP:DEBUG-PORT' with the current debugging port.
Devices:
$device_summary"
    fi
    fail "no ready ADB watch was found. Enable Wireless debugging, pair the computer, then connect with the separate debugging port. Run 'adb devices -l' and confirm the state says 'device'.
Devices:
$device_summary"
  fi
  if [ "$ready_count" -gt 1 ]; then
    fail "more than one ready ADB device was found:
$ready_serials
To avoid authorizing the wrong device, rerun with --serial SERIAL using the exact watch value from 'adb devices -l'."
  fi
  serial=$(printf '%s\n' "$ready_serials" | awk 'NF { print; exit }')
fi

validate_serial

adb_device() {
  "$adb_path" -s "$serial" "$@"
}

sdk_level_raw=$(adb_device shell getprop ro.build.version.sdk) ||
  fail "could not read the Android API level from '$serial'"
sdk_level=$(printf '%s' "$sdk_level_raw" | tr -d '\r')
case "$sdk_level" in
  ''|*[!0-9]*) fail "invalid Android API level '$sdk_level'" ;;
esac
[ "$sdk_level" -ge 30 ] ||
  fail "Android API $sdk_level is unsupported; Wear OS 3 / API 30 or newer is required"

manufacturer_raw=$(adb_device shell getprop ro.product.manufacturer) ||
  fail "could not read the watch manufacturer from '$serial'"
manufacturer=$(printf '%s' "$manufacturer_raw" | tr -d '\r')
model_raw=$(adb_device shell getprop ro.product.model) ||
  fail "could not read the watch model from '$serial'"
model=$(printf '%s' "$model_raw" | tr -d '\r')
features_raw=$(adb_device shell pm list features) ||
  fail "could not read Android features from '$serial'"
features=$(printf '%s' "$features_raw" | tr -d '\r')
if ! printf '%s\n' "$features" | grep -qx 'feature:android.hardware.type.watch'; then
  if [ "$allow_non_wear" != 'true' ]; then
    fail "the selected device does not report android.hardware.type.watch. Tailware's helper refuses non-Wear devices by default. If this is an intentional emulator or unusual Wear build, inspect the device summary and rerun with --allow-non-wear."
  fi
  printf '%s\n' 'Warning: this device does not report android.hardware.type.watch. Continuing only because --allow-non-wear was explicitly supplied.' >&2
fi

user_id=''
if user_id_raw=$(adb_device shell am get-current-user 2>/dev/null); then
  user_id=$(printf '%s' "$user_id_raw" | tr -d '\r')
fi
case "$user_id" in
  ''|*[!0-9]*)
    user_id=''
    if user_id_raw=$(adb_device shell cmd activity get-current-user 2>/dev/null); then
      user_id=$(printf '%s' "$user_id_raw" | tr -d '\r')
    fi
    ;;
esac
[ -n "$user_id" ] ||
  fail 'could not determine the current Android user with either Android activity command'
case "$user_id" in
  *[!0-9]*) fail "could not determine the current Android user ('$user_id')" ;;
esac

package_path_raw=$(adb_device shell pm path --user "$user_id" "$package_id") ||
  fail "could not query package '$package_id' for active Android user $user_id"
package_path=$(printf '%s' "$package_path_raw" | tr -d '\r')
printf '%s\n' "$package_path" | grep -Eq '^package:[^[:space:]]+' ||
  fail "Tailware package '$package_id' is not installed for the active watch user ($user_id). You may be connected to the wrong device or have installed a different APK. Install Tailware on this watch and retry; only use --package if you intentionally built another application ID."

get_secure_setting() {
  secure_value_raw=$(adb_device shell settings --user "$user_id" get secure "$1") ||
    return 1
  secure_value=$(printf '%s' "$secure_value_raw" | tr -d '\r')
  [ "$secure_value" != 'null' ] || secure_value=''
  printf '%s' "$secure_value"
}

read_always_on_settings_snapshot() {
  snapshot_lockdown=$(get_secure_setting always_on_vpn_lockdown) || return 1
  snapshot_allowlist=$(get_secure_setting always_on_vpn_lockdown_whitelist) || return 1
  # Ownership is deliberately the final ADB operation in the snapshot. Callers use this only
  # immediately before mutation, never as authorization retained across the confirmation prompt.
  snapshot_always_on=$(get_secure_setting always_on_vpn_app) || return 1
}

assert_always_on_owner_mutable() {
  mutable_owner=$(get_secure_setting always_on_vpn_app) || {
    assertion_error='could not re-read always-on VPN ownership'
    return 1
  }
  if [ -n "$mutable_owner" ] && [ "$mutable_owner" != "$package_id" ]; then
    assertion_error="always-on VPN ownership changed to '$mutable_owner'; refusing to modify another provider"
    return 1
  fi
  return 0
}

set_secure_setting() {
  setting_name=$1
  setting_value=$2
  if [ -n "$setting_value" ]; then
    adb_device shell settings --user "$user_id" put secure "$setting_name" "$setting_value"
  else
    adb_device shell settings --user "$user_id" delete secure "$setting_name"
  fi
}

set_guarded_always_on_setting() {
  assertion_error=''
  assert_always_on_owner_mutable || return 1
  set_secure_setting "$1" "$2"
}

get_activate_vpn_appop() {
  appop_output_raw=$(adb_device shell appops get --user "$user_id" "$package_id" ACTIVATE_VPN) ||
    return 1
  printf '%s' "$appop_output_raw" | tr -d '\r'
}

parse_appop_mode() {
  parsed_mode=$(printf '%s\n' "$1" |
    sed -nE 's/^[[:space:]]*(ACTIVATE_VPN|android:activate_vpn)[[:space:]]*:[[:space:]]*([a-z_]+).*/\2/p' |
    head -n 1)
  if [ -n "$parsed_mode" ]; then
    printf '%s' "$parsed_mode"
    return 0
  fi
  if printf '%s\n' "$1" | grep -Eiq '^[[:space:]]*(No operations|No ops found)\.?[[:space:]]*$'; then
    printf 'default'
    return 0
  fi
  return 1
}

set_activate_vpn_appop() {
  adb_device shell appops set --user "$user_id" "$package_id" ACTIVATE_VPN "$1"
}

assert_activate_vpn_appop() {
  expected_appop_mode=$1
  asserted_appop_output=$(get_activate_vpn_appop) || {
    assertion_error='could not read ACTIVATE_VPN during verification'
    return 1
  }
  asserted_appop_mode=$(parse_appop_mode "$asserted_appop_output") || {
    assertion_error="could not parse ACTIVATE_VPN during verification: $asserted_appop_output"
    return 1
  }
  if [ "$asserted_appop_mode" != "$expected_appop_mode" ]; then
    assertion_error="expected ACTIVATE_VPN '$expected_appop_mode', received '$asserted_appop_mode': $asserted_appop_output"
    return 1
  fi
  return 0
}

assert_always_on_settings() {
  expected_always_on=$1
  expected_lockdown=$2
  expected_allowlist=$3
  actual_lockdown=$(get_secure_setting always_on_vpn_lockdown) || {
    assertion_error='could not read always_on_vpn_lockdown during verification'
    return 1
  }
  actual_allowlist=$(get_secure_setting always_on_vpn_lockdown_whitelist) || {
    assertion_error='could not read always_on_vpn_lockdown_whitelist during verification'
    return 1
  }
  # Read the security-critical owner last so a provider takeover between verification reads is
  # reflected in the value that decides success.
  actual_always_on=$(get_secure_setting always_on_vpn_app) || {
    assertion_error='could not read always_on_vpn_app during verification'
    return 1
  }
  if [ "$actual_always_on" != "$expected_always_on" ] ||
    [ "$actual_lockdown" != "$expected_lockdown" ] ||
    [ "$actual_allowlist" != "$expected_allowlist" ]; then
    assertion_error="always-on verification failed (app='$actual_always_on', lockdown='$actual_lockdown', allowlist='$actual_allowlist')"
    return 1
  fi
  return 0
}

restore_always_on_settings() {
  previous_always_on=$1
  previous_lockdown=$2
  previous_allowlist=$3
  assertion_error=''
  if [ -n "$previous_always_on" ] && [ "$previous_always_on" != "$package_id" ]; then
    assertion_error="refusing to restore foreign always-on VPN provider '$previous_always_on'"
    return 1
  fi
  assert_always_on_owner_mutable || return 1
  # Release lockdown before detaching the temporary provider. Restore the
  # previous provider last, after its policy and allowlist are ready.
  if ! set_guarded_always_on_setting always_on_vpn_lockdown '0' >/dev/null; then
    [ -n "$assertion_error" ] ||
      assertion_error='could not release lockdown before restoring previous always-on settings'
    return 1
  fi
  if ! set_guarded_always_on_setting always_on_vpn_app '' >/dev/null; then
    [ -n "$assertion_error" ] ||
      assertion_error='could not detach the temporary always-on provider during rollback'
    return 1
  fi
  if ! set_guarded_always_on_setting always_on_vpn_lockdown_whitelist "$previous_allowlist" >/dev/null; then
    [ -n "$assertion_error" ] ||
      assertion_error='could not restore the previous always-on lockdown allowlist'
    return 1
  fi
  if ! set_guarded_always_on_setting always_on_vpn_lockdown "$previous_lockdown" >/dev/null; then
    [ -n "$assertion_error" ] ||
      assertion_error='could not restore the previous always-on lockdown mode'
    return 1
  fi
  if ! set_guarded_always_on_setting always_on_vpn_app "$previous_always_on" >/dev/null; then
    [ -n "$assertion_error" ] ||
      assertion_error='could not restore the previous always-on provider'
    return 1
  fi
  assert_always_on_settings "$previous_always_on" "$previous_lockdown" "$previous_allowlist"
}

current_appop=$(get_activate_vpn_appop) ||
  fail 'could not read the current ACTIVATE_VPN AppOp'
current_appop_mode=$(parse_appop_mode "$current_appop") ||
  fail "could not determine the current ACTIVATE_VPN mode. AppOps output:
$current_appop"
read_always_on_settings_snapshot ||
  fail 'could not read the existing always-on VPN settings'
always_on=$snapshot_always_on
lockdown=$snapshot_lockdown
allowlist=$snapshot_allowlist
display_always_on=$always_on
[ -n "$display_always_on" ] || display_always_on='(none)'

printf '\nTailware Wear VPN authorization\n'
printf '  Device       : %s %s\n' "$manufacturer" "$model"
printf '  Serial       : %s\n' "$serial"
printf '  ADB tools    : %s\n' "$adb_version"
printf '  Android API  : %s\n' "$sdk_level"
printf '  Android user : %s\n' "$user_id"
printf '  Package      : %s\n' "$package_id"
printf '  Current AppOp: %s\n' "$current_appop"
printf '  Always-on VPN: %s\n\n' "$display_always_on"

if [ "$enable_always_on" = 'true' ] &&
  [ -n "$always_on" ] &&
  [ "$always_on" != "$package_id" ]; then
  fail "refusing to replace existing always-on VPN '$always_on'. Disable it explicitly before retrying."
fi
if [ "$revoke" != 'true' ] &&
  [ "$enable_always_on" != 'true' ] &&
  [ -n "$always_on" ] &&
  [ "$always_on" != "$package_id" ]; then
  printf "Warning: another app ('%s') is configured as always-on VPN. Android can refuse to prepare Tailware until that setting is removed.\n" "$always_on" >&2
fi

if [ "$dry_run" = 'true' ]; then
  printf 'Dry run: no device changes were made.\n'
  exit 0
fi

if [ "$assume_yes" != 'true' ]; then
  if [ "$revoke" = 'true' ]; then
    confirmation_text="Revoke ACTIVATE_VPN from '$package_id' on this device? [y/N] "
  elif [ "$enable_always_on" = 'true' ]; then
    confirmation_text="Grant ACTIVATE_VPN and configure '$package_id' as always-on without lockdown? [y/N] "
  else
    confirmation_text="Grant ACTIVATE_VPN to '$package_id' on this device? [y/N] "
  fi
  printf '%s' "$confirmation_text"
  read -r answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *)
      printf 'No device changes were made.\n'
      exit 0
      ;;
  esac
fi

# Confirmation can remain open indefinitely. Capture the rollback AppOp baseline only after the
# user has authorized mutation, immediately before the first write.
current_appop=$(get_activate_vpn_appop) ||
  fail 'could not refresh the current ACTIVATE_VPN AppOp after confirmation'
current_appop_mode=$(parse_appop_mode "$current_appop") ||
  fail "could not determine the post-confirmation ACTIVATE_VPN mode. AppOps output:
$current_appop"

if [ "$revoke" = 'true' ] || [ "$enable_always_on" = 'true' ]; then
  read_always_on_settings_snapshot ||
    fail 'could not refresh always-on VPN settings after confirmation'
  mutation_always_on=$snapshot_always_on
  mutation_lockdown=$snapshot_lockdown
  mutation_allowlist=$snapshot_allowlist
  if [ "$enable_always_on" = 'true' ] &&
    [ -n "$mutation_always_on" ] &&
    [ "$mutation_always_on" != "$package_id" ]; then
    fail "always-on VPN ownership changed to '$mutation_always_on'; refusing to modify another provider"
  fi
fi

if [ "$revoke" = 'true' ]; then
  always_on=$mutation_always_on
  lockdown=$mutation_lockdown
  allowlist=$mutation_allowlist
  always_on_changed='false'
  revoke_error=''
  if [ "$always_on" = "$package_id" ]; then
    always_on_changed='true'
    # Disable lockdown first, detach Tailware, then remove only Tailware-owned
    # policy values. Never mutate another provider's settings.
    if ! set_guarded_always_on_setting always_on_vpn_lockdown '0' >/dev/null 2>&1; then
      revoke_error=${assertion_error:-'could not disable lockdown before removing Tailware always-on'}
    fi
    if [ -z "$revoke_error" ]; then
      if ! set_guarded_always_on_setting always_on_vpn_app '' >/dev/null 2>&1; then
        revoke_error=${assertion_error:-'could not detach Tailware as always-on provider'}
      fi
    fi
    if [ -z "$revoke_error" ]; then
      if ! set_guarded_always_on_setting always_on_vpn_lockdown_whitelist '' >/dev/null 2>&1; then
        revoke_error=${assertion_error:-'could not clear Tailware always-on allowlist'}
      fi
    fi
    if [ -z "$revoke_error" ]; then
      if ! set_guarded_always_on_setting always_on_vpn_lockdown '' >/dev/null 2>&1; then
        revoke_error=${assertion_error:-'could not clear Tailware lockdown setting'}
      fi
    fi
    if [ -z "$revoke_error" ] &&
      ! assert_always_on_settings '' '' ''; then
      revoke_error=$assertion_error
    fi
  elif [ -n "$always_on" ]; then
    printf "Warning: always-on belongs to '%s'; revoke mode preserves that provider and all of its lockdown settings.\n" "$always_on" >&2
  fi

  if [ -z "$revoke_error" ]; then
    set_activate_vpn_appop default >/dev/null 2>&1 ||
      revoke_error='could not restore ACTIVATE_VPN to default'
  fi
  if [ -z "$revoke_error" ] &&
    ! assert_activate_vpn_appop default; then
    revoke_error=$assertion_error
  fi

  if [ -n "$revoke_error" ]; then
    rollback_error=''
    if ! set_activate_vpn_appop "$current_appop_mode" >/dev/null 2>&1; then
      rollback_error="ACTIVATE_VPN AppOp: could not restore '$current_appop_mode'"
    elif ! assert_activate_vpn_appop "$current_appop_mode"; then
      rollback_error="ACTIVATE_VPN AppOp: $assertion_error"
    fi
    if [ "$always_on_changed" = 'true' ] &&
      ! restore_always_on_settings "$always_on" "$lockdown" "$allowlist"; then
      [ -z "$rollback_error" ] ||
        rollback_error="$rollback_error; "
      rollback_error="${rollback_error}always-on settings: $assertion_error"
    fi
    if [ -n "$rollback_error" ]; then
      fail "revoke failed and rollback could not be verified. Original error: $revoke_error. Rollback error(s): $rollback_error"
    fi
    fail "revoke failed; the previous state was restored and verified. $revoke_error"
  fi

  printf 'VPN authorization revoked: %s\n' "$asserted_appop_output"
  if [ "$always_on_changed" = 'true' ]; then
    printf 'Tailware-owned always-on settings were removed safely.\n'
  fi
  printf 'Reopen Tailware. Its setup screen should now request authorization.\n'
  exit 0
fi

grant_error=''
set_activate_vpn_appop allow >/dev/null 2>&1 ||
  grant_error='could not set ACTIVATE_VPN to allow'
if [ -z "$grant_error" ] &&
  ! assert_activate_vpn_appop allow; then
  grant_error=$assertion_error
fi
if [ -n "$grant_error" ]; then
  rollback_error=''
  set_activate_vpn_appop "$current_appop_mode" >/dev/null 2>&1 ||
    rollback_error="could not restore ACTIVATE_VPN '$current_appop_mode'"
  if [ -z "$rollback_error" ] &&
    ! assert_activate_vpn_appop "$current_appop_mode"; then
    rollback_error=$assertion_error
  fi
  if [ -n "$rollback_error" ]; then
    fail "VPN authorization failed and the previous AppOp could not be restored. Original error: $grant_error. Rollback error: $rollback_error"
  fi
  fail "VPN authorization failed; the previous AppOp '$current_appop_mode' was restored and verified. $grant_error"
fi

printf 'VPN authorization confirmed: %s\n' "$asserted_appop_output"

if [ "$enable_always_on" = 'true' ]; then
  always_on_error=''
  always_on_mutation_attempted='false'
  # AppOp verification can race another provider taking ownership. Refresh the complete rollback
  # baseline now, with ownership read last, and refuse before the first settings write.
  if ! read_always_on_settings_snapshot; then
    always_on_error='could not refresh always-on settings after AppOp authorization'
  elif [ -n "$snapshot_always_on" ] && [ "$snapshot_always_on" != "$package_id" ]; then
    always_on_error="always-on VPN ownership changed to '$snapshot_always_on'; refusing to modify another provider"
  else
    always_on=$snapshot_always_on
    lockdown=$snapshot_lockdown
    allowlist=$snapshot_allowlist
  fi
  # Keep lockdown disabled before attaching Tailware to avoid a transient
  # network lockout if Android applies each setting immediately.
  if [ -z "$always_on_error" ]; then
    always_on_mutation_attempted='true'
    if ! set_guarded_always_on_setting always_on_vpn_lockdown '0' >/dev/null 2>&1; then
      always_on_error=${assertion_error:-'could not disable lockdown'}
    fi
  fi
  if [ -z "$always_on_error" ]; then
    if ! set_guarded_always_on_setting always_on_vpn_lockdown_whitelist '' >/dev/null 2>&1; then
      always_on_error=${assertion_error:-'could not clear the lockdown allowlist'}
    fi
  fi
  if [ -z "$always_on_error" ]; then
    if ! set_guarded_always_on_setting always_on_vpn_app "$package_id" >/dev/null 2>&1; then
      always_on_error=${assertion_error:-'could not set Tailware as always-on provider'}
    fi
  fi
  if [ -z "$always_on_error" ] &&
    ! assert_always_on_settings "$package_id" '0' ''; then
    always_on_error=$assertion_error
  fi

  if [ -n "$always_on_error" ]; then
    rollback_error=''
    if [ "$always_on_mutation_attempted" = 'true' ] &&
      ! restore_always_on_settings "$always_on" "$lockdown" "$allowlist"; then
      rollback_error="always-on settings: $assertion_error"
    fi
    if ! set_activate_vpn_appop "$current_appop_mode" >/dev/null 2>&1; then
      [ -z "$rollback_error" ] ||
        rollback_error="$rollback_error; "
      rollback_error="${rollback_error}could not restore ACTIVATE_VPN '$current_appop_mode'"
    elif ! assert_activate_vpn_appop "$current_appop_mode"; then
      [ -z "$rollback_error" ] ||
        rollback_error="$rollback_error; "
      rollback_error="${rollback_error}ACTIVATE_VPN AppOp: $assertion_error"
    fi
    if [ -n "$rollback_error" ]; then
      fail "always-on setup failed and rollback could not be verified. Original error: $always_on_error. Rollback error(s): $rollback_error"
    fi
    fail "always-on setup failed; its previous settings and AppOp '$current_appop_mode' were restored and verified. $always_on_error"
  fi
  printf 'Always-on VPN was stored with lockdown disabled.\n'
  printf '%s\n' 'Warning: restart the watch before relying on always-on mode. First verify a normal Tailware connection.' >&2
fi

printf 'Reopen Tailware. The setup screen will detect the authorization automatically.\n'
