{
  description = "Cordis Android runtime bootstrap";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
      boilerplateVersion = "v0.6.0";
      boilerplateAsset = "boilerplate-v0.6.0-linux-arm64-node24.zip";
      boilerplate = pkgs.fetchurl {
        url = "https://github.com/cordiverse/boilerplate/releases/download/${boilerplateVersion}/${boilerplateAsset}";
        hash = "sha256-5BXU+mieF5K+HM9Z5sbOMviLnn8Fqys8TbJjjemMi3Q=";
      };
    in
    {
      packages.${system}.default = pkgs.stdenvNoCC.mkDerivation {
        pname = "cordis-android-bootstrap";
        version = "0.1.0";
        dontUnpack = true;

        installPhase = ''
          mkdir -p $out/assets/bootstrap $out/template
          cp ${../app/src/main/assets/bootstrap/default-cordis.yml} $out/template/cordis.yml
          cp ${boilerplate} $out/assets/bootstrap/boilerplate.zip
          cat > $out/assets/bootstrap/boilerplate.txt <<EOF
          source=https://github.com/cordiverse/boilerplate/releases/download/${boilerplateVersion}/${boilerplateAsset}
          version=${boilerplateVersion}
          asset=${boilerplateAsset}
          sha256=e415d4fa689e1792be1ccf59e6c6ce32f88b9e7f05ab2b3c4db2638de98c8b74
          EOF
          cat > $out/README.txt <<'EOF'
          This bootstrap derivation vendors the Cordis boilerplate release used
          as the default Android instance template.

          Next step: replace it with the Android proot closure containing
          proot-static, busybox, Node.js, CA certificates, and shims.
          EOF
        '';
      };
    };
}
