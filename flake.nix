{
  description = "Flake to manage clj-gdal builds";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # A checkout: nix develop --override-input clj-native path:/abs/path
    clj-native.url = "github:willcohen/clj-native";
    clj-native.inputs.nixpkgs.follows = "nixpkgs";
    clj-native.inputs.flake-utils.follows = "flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, clj-native, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        native = clj-native.lib.${system};
        pkgs = nixpkgs.legacyPackages.${native.actualSystem};
      in {
        devShells = native.mkCrossShells {
          # libgraal JIT-compiles the wasm guest. Keep the org.graalvm.*
          # versions of deps.edn equal to this JDK.
          jdk = pkgs.graalvmPackages.graalvm-ce;

          # The PROJ build runs sqlite3 to make proj.db.
          extraBuildInputs = [ pkgs.sqlite ];

          # Not nodejs (v24): its libuv stops the process at the shutdown of
          # a pool whose workers did network I/O.
          extraDevInputs = with pkgs; [
            act
            clang
            emscripten
            nodejs_26
          ];
        };
      }
    );
}
