{
  description = "train-fusion: Caltrain GTFS-RT feed archiver";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        python = pkgs.python312.withPackages (ps: with ps; [
          gtfs-realtime-bindings
          protobuf
        ]);
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [ python pkgs.curl pkgs.jq ];
        };
      });
}
