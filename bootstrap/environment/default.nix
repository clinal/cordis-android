{ pkgs, buildEnv, callPackage, lib, inputs, full ? false }:

let
  aarch64-pkgs = import inputs.nixpkgs { system = "aarch64-linux"; };
  aarch64-node-pkgs = import inputs.node-pkgs { system = "aarch64-linux"; };
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
    aarch64-node-pkgs.nodejs-slim_24
  ] ++ (lib.optionals full [
    fonts
    chromium
    timezone
  ]);
}
