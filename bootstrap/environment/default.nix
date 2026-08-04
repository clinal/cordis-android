{ pkgs, buildEnv, callPackage, lib, inputs, full ? false, runtimeSystem ? "aarch64-linux" }:

let
  runtime-pkgs = import inputs.nixpkgs { system = runtimeSystem; };
  runtime-node-pkgs = import inputs.node-pkgs { system = runtimeSystem; };
  login = callPackage ./login.nix { };
  env = callPackage ./env.nix { inherit (runtime-pkgs) busybox; };
  certs = callPackage ./certs.nix { };
  fonts = callPackage ./fonts.nix { };
  timezone = callPackage ./timezone.nix { };
in
buildEnv {
  name = "cordis-env";
  paths = with runtime-pkgs; [
    login
    env
    certs
    busybox
    zip
    runtime-node-pkgs.pnpm
    runtime-node-pkgs.yarn-berry
    runtime-node-pkgs.nodejs-slim_26
  ] ++ (lib.optionals full [
    fonts
    chromium
    timezone
  ]);
}
