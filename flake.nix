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
      gradle = pkgs.writeShellScriptBin "gradle" ''
        exec ${pkgs.gradle}/bin/gradle \
          -Pandroid.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2 \
          "$@"
      '';
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          gradle
          pkgs.jdk17
          androidSdk
          pkgs.file
          pkgs.qemu
          pkgs.unzip
        ];

        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        JAVA_HOME = pkgs.jdk17.home;
      };
    };
}
