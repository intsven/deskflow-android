#!/bin/bash
# deskflow-android on-device smoke test: install state, launch, no-crash.
# Uses direct ADB to the RPi5 (adb -s 100.77.45.20:5555 over Tailscale).
# Usage: ./verify_ondevice_smoke.sh

ADB="${ADB:-adb} -s 100.77.45.20:5555"
PKG="org.tfv.deskflow"

PASS=0
FAIL=0

green() { echo -e "\033[32m✓ $1\033[0m"; }
red() { echo -e "\033[31m✗ $1\033[0m"; }
info() { echo -e "\033[34mℹ $1\033[0m"; }

header() {
    echo ""
    echo "═══════════════════════════════════════════"
    echo "  $1"
    echo "═══════════════════════════════════════════"
}

header "DESKFLOW ON-DEVICE SMOKE TEST"

info "Checking package installed..."
if $ADB shell pm list packages 2>/dev/null | grep -q "$PKG"; then
    green "$PKG installed"
    PASS=$((PASS + 1))
else
    red "$PKG NOT installed"
    FAIL=$((FAIL + 1))
fi

info "Launching RootActivity..."
$ADB shell logcat -c >/dev/null 2>&1 || true
$ADB shell am start -n $PKG/.ui.activities.RootActivity >/dev/null 2>&1 || true
sleep 6

if $ADB shell pidof $PKG 2>/dev/null | grep -q "[0-9]"; then
    green "App process alive after launch"
    PASS=$((PASS + 1))
else
    red "App process NOT alive after launch"
    FAIL=$((FAIL + 1))
fi

if $ADB shell logcat -d 2>/dev/null | grep "FATAL EXCEPTION" | grep -q "deskflow"; then
    red "FATAL EXCEPTION from deskflow in logcat"
    FAIL=$((FAIL + 1))
else
    green "No deskflow crash in logcat"
    PASS=$((PASS + 1))
fi

header "DESKFLOW SMOKE SUMMARY"
echo "  PASSED: $PASS"
echo "  FAILED: $FAIL"
echo ""
if [ $FAIL -eq 0 ]; then
    green "All on-device smoke checks passed!"
else
    red "$FAIL check(s) failed"
fi
exit $FAIL
