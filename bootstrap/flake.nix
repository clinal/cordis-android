{
  description = "Cordis Android runtime bootstrap";

  nixConfig = {
    extra-substituters = [ "https://nix-on-droid.cachix.org" ];
    extra-trusted-public-keys = [ "nix-on-droid.cachix.org-1:56snoMJTXmDRC1Ei24CmKoUqvHJ9XCp+nidK7qkMQrU=" ];
  };

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils/1ed9fb1935d260de5fe1c2f7ee0ebaae17ed2fa1";
  inputs.anillc.url = "github:Anillc/flakes/be4ce4f0a20c2be33cf3d99ed34f20b350c07016";
  inputs.nix-on-droid.url = "github:nix-community/nix-on-droid/55b6449b4582a4ba3ce712543c973360a026db7d";

  outputs = inputs@{ self, nixpkgs, flake-utils, anillc, nix-on-droid }:
    flake-utils.lib.eachDefaultSystem (system:
    let
      pkgs = import nixpkgs { inherit system; };
      boilerplateVersion = "v0.6.1";
      boilerplates = {
        aarch64-linux = {
          asset = "boilerplate-v0.6.1-linux-arm64-node24.zip";
          hash = "sha256-TeOvkWAEPxTML6XMtf/bv90oe1BaYwDcxEYCcwOBxXI=";
          sha256 = "4de3af9160043f14cc2fa5ccb5ffdbbfdd287b505a6300dcc44602730381c572";
        };
        x86_64-linux = {
          asset = "boilerplate-v0.6.1-linux-amd64-node24.zip";
          hash = "sha256-gYG5bqSOphaZM1BGwmj0LPVAq2LrZTa+OTac7yq4fII=";
          sha256 = "8181b96ea48ea61699335046c268f42cf540ab62eb6536be39369cef2ab87c82";
        };
      };
      runtimeFor = runtimeSystem: full: pkgs.callPackage ./bootstrap.nix {
        inherit inputs full runtimeSystem;
        prootTermux = nix-on-droid.packages.${system}.${if runtimeSystem == "x86_64-linux" then "prootTermux-x86_64" else "prootTermux-aarch64"};
        pkgs = pkgs // anillc.packages.${system} // nix-on-droid.packages.${system};
      };
      bootstrap = runtimeFor "aarch64-linux" false;
      bootstrapExtra = runtimeFor "aarch64-linux" true;
      bootstrapX86_64 = runtimeFor "x86_64-linux" false;
      packageAssets = runtimeSystem: runtime:
      let
        boilerplateInfo = boilerplates.${runtimeSystem};
        boilerplate = pkgs.fetchurl {
          url = "https://github.com/cordiverse/boilerplate/releases/download/${boilerplateVersion}/${boilerplateInfo.asset}";
          hash = boilerplateInfo.hash;
        };
      in
      pkgs.runCommand "cordis-android-bootstrap-assets-${runtimeSystem}" { } ''
        mkdir -p $out/assets/bootstrap
        cp ${runtime}/bootstrap.zip $out/assets/bootstrap/bootstrap.zip
        cp ${runtime}/env.txt $out/assets/bootstrap/env.txt
        cp ${boilerplate} $out/assets/bootstrap/boilerplate.zip
        cat > $out/assets/bootstrap/boilerplate.txt <<EOF
        source=https://github.com/cordiverse/boilerplate/releases/download/${boilerplateVersion}/${boilerplateInfo.asset}
        version=${boilerplateVersion}
        asset=${boilerplateInfo.asset}
        sha256=${boilerplateInfo.sha256}
        EOF
        cat > $out/README.txt <<EOF
        This package contains Android bootstrap assets:
        - bootstrap.zip: proot-static plus a ${runtimeSystem} Nix closure
        - env.txt: the closure root path used as the proot root
        - boilerplate.zip: default Cordis project template
        EOF
      '';
    in
    {
      packages = {
        runtime = bootstrap;
        runtime-extra = bootstrapExtra;
        runtime-x86_64 = bootstrapX86_64;
        default = packageAssets "aarch64-linux" bootstrap;
        extra = packageAssets "aarch64-linux" bootstrapExtra;
        x86_64 = packageAssets "x86_64-linux" bootstrapX86_64;
      };
    });
}
