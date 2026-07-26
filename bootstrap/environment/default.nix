{ pkgs, buildEnv, callPackage, lib, inputs, full ? false }:

let
  aarch64-pkgs = import inputs.nixpkgs { system = "aarch64-linux"; };
  login = callPackage ./login.nix { };
  env = callPackage ./env.nix { inherit (aarch64-pkgs) busybox; };
  certs = callPackage ./certs.nix { };
  fonts = callPackage ./fonts.nix { };
  timezone = callPackage ./timezone.nix { };
in
buildEnv {
  name = "cordis-env";
  paths = with aarch64-pkgs; [
    login
    env
    certs
    busybox
    zip
    nodejs
  ] ++ (lib.optionals full [
    fonts
    chromium
    timezone
  ]);
}
