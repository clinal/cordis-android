{
  description = "Cordis Android runtime bootstrap";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
    in
    {
      packages.${system}.default = pkgs.stdenvNoCC.mkDerivation {
        pname = "cordis-android-bootstrap";
        version = "0.1.0";
        dontUnpack = true;

        installPhase = ''
          mkdir -p $out/template
          cp ${../app/src/main/assets/bootstrap/default-cordis.yml} $out/template/cordis.yml
          cat > $out/README.txt <<'EOF'
          This is a placeholder bootstrap derivation.

          Next step: replace it with the Android proot closure containing
          proot-static, busybox, Node.js, CA certificates, Cordis, and shims.
          EOF
        '';
      };
    };
}
