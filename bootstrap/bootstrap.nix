{ pkgs, callPackage, lib, inputs, full ? false, ... }:

let
  env = callPackage ./environment { inherit full inputs; };
  closureInfo = pkgs.closureInfo { rootPaths = [ env ]; };
  bootstrap = pkgs.runCommand "cordis-bootstrap-root" { } ''
    mkdir -p $out/nix/store
    while read -r storePath; do
      cp -r "$storePath" "$out/nix/store"
    done < ${closureInfo}/store-paths

    cp ${pkgs.prootTermux}/bin/proot-static $out/proot-static
    chmod -R u+w $out/nix $out/proot-static

    find $out -executable -type f | sed "s@^$out/@@" > $out/EXECUTABLES.txt
    find $out -type l | while read -r link; do
      relative="''${link#$out/}"
      target="$(readlink "$link")"
      printf "%s←%s\n" "$target" "$relative" >> $out/SYMLINKS.txt
      rm "$link"
    done
  '';
in
pkgs.runCommand "cordis-bootstrap" { } ''
  mkdir -p $out
  cd ${bootstrap}
  ${pkgs.zip}/bin/zip -q -9 -r $out/bootstrap.zip ./*
  echo ${env} > $out/env.txt
''
