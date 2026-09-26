#!/bin/bash
# 为待侧载的包保留 App Groups 声明；临时 ad-hoc 签名不代替 AltStore 真正的重签。
set -euo pipefail

APP_PATH="${1:?Usage: prepare-widget.sh /path/to/MCDevManagerMPR.app}"
WIDGET_PATH="$APP_PATH/PlugIns/IncomeWidget.appex"
test -f "$APP_PATH/Info.plist"
test -f "$WIDGET_PATH/Info.plist"
PLIST=/usr/libexec/PlistBuddy
APP_ID=$($PLIST -c 'Print :CFBundleIdentifier' "$APP_PATH/Info.plist")
WIDGET_ID=$($PLIST -c 'Print :CFBundleIdentifier' "$WIDGET_PATH/Info.plist")
GROUP_ID=$($PLIST -c 'Print :IncomeWidgetAppGroup' "$APP_PATH/Info.plist")
test "$WIDGET_ID" = "$APP_ID.IncomeWidget"
test "$GROUP_ID" = "$($PLIST -c 'Print :IncomeWidgetAppGroup' "$WIDGET_PATH/Info.plist")"
test "$($PLIST -c 'Print :NSExtension:NSExtensionPointIdentifier' "$WIDGET_PATH/Info.plist")" = 'com.apple.widgetkit-extension'
for KEY in CFBundleVersion CFBundleShortVersionString; do
    test "$($PLIST -c "Print :$KEY" "$APP_PATH/Info.plist")" = "$($PLIST -c "Print :$KEY" "$WIDGET_PATH/Info.plist")"
done
case "$GROUP_ID" in group.*) ;; *) exit 1 ;; esac
# 未展开的 Xcode 变量不能作为真正的 entitlement。
case "$GROUP_ID" in *'$('*|*'${'*) exit 1 ;; esac

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
ENTITLEMENTS="$TEMP_DIR/widget.entitlements"
$PLIST -c 'Add :com.apple.security.application-groups array' "$ENTITLEMENTS"
$PLIST -c "Add :com.apple.security.application-groups:0 string $GROUP_ID" "$ENTITLEMENTS"

# CODE_SIGNING_ALLOWED=NO 的裸二进制不携带权限；先内后外做临时签名，让侧载器能读取分组。
while IFS= read -r -d '' CODE; do
    codesign --force --sign - "$CODE"
done < <(find "$APP_PATH" -depth \( -name '*.framework' -o -name '*.dylib' \) -print0)
codesign --force --sign - --entitlements "$ENTITLEMENTS" --generate-entitlement-der "$WIDGET_PATH"
codesign --force --sign - --entitlements "$ENTITLEMENTS" --generate-entitlement-der "$APP_PATH"
codesign --verify --deep --strict "$APP_PATH"

for BUNDLE in "$APP_PATH" "$WIDGET_PATH"; do
    codesign --display --entitlements :- "$BUNDLE" > "$TEMP_DIR/actual.plist" 2>/dev/null
    test "$($PLIST -c 'Print :com.apple.security.application-groups:0' "$TEMP_DIR/actual.plist")" = "$GROUP_ID"
done
printf '%s\n' 'Widget extension, versions and shared-group entitlements verified; re-sign before installing.'
