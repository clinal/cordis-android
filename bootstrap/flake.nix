{
  description = "Cordis Android runtime bootstrap";

  # Locked to the same bootstrap-era revisions used by Koishi Android.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/6616de389ed55fba6eeba60377fc04732d5a207c";
  inputs.flake-utils.url = "github:numtide/flake-utils/1ed9fb1935d260de5fe1c2f7ee0ebaae17ed2fa1";
  inputs.anillc.url = "github:Anillc/flakes/be4ce4f0a20c2be33cf3d99ed34f20b350c07016";
  inputs.node-pkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  inputs.nix-on-droid = {
    url = "github:Anillc/nix-on-droid/ead1bfcf5610ef4800a46deaf54162f0bed66262";
    inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = inputs@{ self, nixpkgs, flake-utils, anillc, node-pkgs, nix-on-droid }:
    flake-utils.lib.eachDefaultSystem (system:
    let
      pkgs = import nixpkgs { inherit system; };
      boilerplateVersion = "v0.6.1";
      boilerplateAsset = "boilerplate-v0.6.1-linux-arm64-node24.zip";
      boilerplate = pkgs.fetchurl {
        url = "https://github.com/cordiverse/boilerplate/releases/download/${boilerplateVersion}/${boilerplateAsset}";
        hash = "sha256-TeOvkWAEPxTML6XMtf/bv90oe1BaYwDcxEYCcwOBxXI=";
      };
      bootstrap = pkgs.callPackage ./bootstrap.nix {
        inherit inputs;
        pkgs = pkgs // anillc.packages.${system} // nix-on-droid.packages.${system};
      };
      bootstrapExtra = pkgs.callPackage ./bootstrap.nix {
        inherit inputs;
        full = true;
        pkgs = pkgs // anillc.packages.${system} // nix-on-droid.packages.${system};
      };
      packageAssets = runtime: pkgs.runCommand "cordis-android-bootstrap-assets" { } ''
        mkdir -p $out/assets/bootstrap
        cp ${runtime}/bootstrap.zip $out/assets/bootstrap/bootstrap.zip
        cp ${runtime}/env.txt $out/assets/bootstrap/env.txt
        cp ${boilerplate} $out/assets/bootstrap/boilerplate.zip
        cat > $out/assets/bootstrap/boilerplate.txt <<EOF
        source=https://github.com/cordiverse/boilerplate/releases/download/${boilerplateVersion}/${boilerplateAsset}
        version=${boilerplateVersion}
        asset=${boilerplateAsset}
        sha256=4de3af9160043f14cc2fa5ccb5ffdbbfdd287b505a6300dcc44602730381c572
        EOF
        cat > $out/README.txt <<'EOF'
        This package contains Android bootstrap assets:
        - bootstrap.zip: proot-static plus an aarch64 Nix closure
        - env.txt: the closure root path used as the proot root
        - boilerplate.zip: default Cordis project template
        EOF
      '';
    in
    {
      packages = {
        runtime = bootstrap;
        runtime-extra = bootstrapExtra;
        default = packageAssets bootstrap;
        extra = packageAssets bootstrapExtra;
      };
    });
}
