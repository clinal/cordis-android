{
  description = "cordis-android development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.android_sdk.accept_license = true;
        config.allowUnfree = true;
      };
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        includeEmulator = false;
        includeSystemImages = false;
      };
      androidSdk = androidComposition.androidsdk;
      androidEmulator = pkgs.androidenv.emulateApp {
        name = "cordis-local-emulator";
        platformVersion = "35";
        abiVersion = "x86_64";
        systemImageType = "google_apis";
        deviceName = "cordis-local-e2e";
        sdkExtraArgs.buildToolsVersions = [ "35.0.0" ];
        androidEmulatorFlags = "-no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -no-metrics";
      };
      gradle = pkgs.writeShellScriptBin "gradle" ''
        exec ${pkgs.gradle}/bin/gradle \
          -Pandroid.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2 \
          "$@"
      '';
      emulatedConnectedAndroidTest = pkgs.writeShellApplication {
        name = "emulated-connected-android-test";
        runtimeInputs = [ androidSdk gradle pkgs.coreutils pkgs.gnugrep pkgs.gnused pkgs.jdk17 ];
        text = ''
          ANDROID_HOME=$(sed -n 's/^export ANDROID_HOME=//p' ${androidEmulator}/bin/run-test-emulator | head -n 1)
          export ANDROID_HOME
          export ANDROID_SDK_ROOT=$ANDROID_HOME
          export JAVA_HOME=${pkgs.jdk17.home}
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

          emulator_log=$(mktemp)
          dump_device_state() {
            echo
            echo "== adb devices =="
            adb devices -l || true
            if [ -n "''${ANDROID_SERIAL:-}" ]; then
              echo
              echo "== emulator log tail =="
              tail -n 200 "$emulator_log" || true
              echo
              echo "== device state =="
              adb -s "$ANDROID_SERIAL" get-state || true
              adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed || true
              adb -s "$ANDROID_SERIAL" shell getprop dev.bootcomplete || true
              echo
              echo "== logcat tail =="
              adb -s "$ANDROID_SERIAL" logcat -d -t 200 || true
            fi
          }
          cleanup() {
            status=$?
            if [ "$status" -ne 0 ]; then
              dump_device_state
            fi
            serial="''${ANDROID_SERIAL:-}"
            if [ -n "$serial" ]; then
              adb -s "$serial" emu kill >/dev/null 2>&1 || true
            fi
            rm -f "$emulator_log"
          }
          trap cleanup EXIT

          adb kill-server >/dev/null 2>&1 || true
          ${androidEmulator}/bin/run-test-emulator > "$emulator_log" 2>&1 &
          for _ in $(seq 1 60); do
            grep -q '^ready$' "$emulator_log" && break
            sleep 5
          done
          grep -q '^ready$' "$emulator_log" || { cat "$emulator_log"; exit 1; }

          cat "$emulator_log"
          emulator_port=$(sed -n 's/.*free TCP port: //p' "$emulator_log" | tail -n 1)
          export ANDROID_SERIAL="emulator-$emulator_port"
          for _ in $(seq 1 90); do
            state=$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)
            boot_completed=$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
            package_manager=$(adb -s "$ANDROID_SERIAL" shell pm path android 2>/dev/null | tr -d '\r' || true)
            if [ "$state" = "device" ] && [ "$boot_completed" = "1" ] && echo "$package_manager" | grep -q '^package:'; then
              break
            fi
            if [ "$state" = "offline" ]; then
              adb reconnect offline >/dev/null 2>&1 || true
            fi
            sleep 5
          done
          state=$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)
          boot_completed=$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
          package_manager=$(adb -s "$ANDROID_SERIAL" shell pm path android 2>/dev/null | tr -d '\r' || true)
          if [ "$state" != "device" ] || [ "$boot_completed" != "1" ] || ! echo "$package_manager" | grep -q '^package:'; then
            echo "emulator did not become stable"
            exit 1
          fi

          adb -s "$ANDROID_SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
          gradle "$@" clean connectedDebugAndroidTest
        '';
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          gradle
          pkgs.jdk17
          androidSdk
        ];

        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        JAVA_HOME = pkgs.jdk17.home;
      };

      apps.${system}.emulated-connected-android-test = {
        type = "app";
        program = "${emulatedConnectedAndroidTest}/bin/emulated-connected-android-test";
      };
    };
}
