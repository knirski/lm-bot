{
  description = "lm-bot — development environment (Scala 3 / sbt 2 / Scala.js Wasm / Postgres)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

    # Pre-commit hooks (from cachix/git-hooks.nix — yes, the "cachix" in the
    # name is the same Cachix that provides binary caches).
    git-hooks.url = "github:cachix/git-hooks.nix";
    git-hooks.inputs.nixpkgs.follows = "nixpkgs";

    # Unified Nix formatter runner (nixfmt, etc.)
    treefmt-nix.url = "github:numtide/treefmt-nix";
    treefmt-nix.inputs.nixpkgs.follows = "nixpkgs";
  };

  # Use the project Cachix binary cache as a substituter so local builds and CI
  # pull pre-built artifacts where possible, reducing duplicate compilation.
  nixConfig = {
    extra-substituters = [
      "https://cache.nixos.org"
      "https://knirski-lm-bot.cachix.org"
    ];
    extra-trusted-public-keys = [
      "cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY="
      "knirski-lm-bot.cachix.org-1:AQwy+9/SNoZ0pIkfUTKnHVO0CXxW+8Cd8KDWVt/PVpE="
    ];
  };

  # Deliberately no flake-parts: this project exposes a single devShell, a
  # formatter, and a handful of checks, so the module-system indirection buys
  # nothing. git-hooks.nix and treefmt-nix are used through their library APIs
  # instead (via `lib.${system}.run` and `lib.evalModule`).
  outputs =
    {
      self,
      nixpkgs,
      git-hooks,
      treefmt-nix,
    }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # Per-system helpers that need pkgs available.
      perSystem =
        pkgs:
        let
          system = pkgs.stdenv.hostPlatform.system;
        in
        rec {
          # -- pre-commit hooks (cachix/git-hooks.nix) -----------------------
          pre-commit = git-hooks.lib.${system}.run {
            src = self;
            # Point the treefmt hook at the treefmt-nix wrapper so it carries
            # the nixfmt config defined below — no standalone treefmt.toml.
            tools = {
              treefmt = treefmt-eval.config.build.wrapper;
            };
            hooks = {
              # Nix formatting via nixfmt (uses the treefmt-nix wrapper).
              treefmt.enable = true;
              # Detect dead Nix code.
              deadnix.enable = true;
              # Lint Nix expressions.
              statix.enable = true;
              # Catch typos in code and docs.
              typos = {
                enable = true;
                settings.config = {
                  default = {
                    "extend-words" = {
                      # In Scala.js/Laminar, `tpe` is the standard SVG/HTML
                      # attribute name because `type` is a reserved word in
                      # Scala.  Not a typo.
                      tpe = "tpe";
                      # Luxmed's API literally returns this misspelling in its
                      # JSON responses; the docs and code quote the API.
                      succeded = "succeded";
                      # PR Agent config key — not a typo.
                      commitable = "commitable";
                    };
                  };
                };
              };
              # Block accidental merge conflict markers.
              check-merge-conflicts.enable = true;
              # Ensure every file ends with a newline.
              end-of-file-fixer.enable = true;
              # Validate GitHub Actions workflow files.
              actionlint.enable = true;
              # Lint shell scripts (shellHook, CI scripts).
              shellcheck.enable = true;
            };
          };

          # -- treefmt (numtide/treefmt-nix) --------------------------------
          treefmt-eval = treefmt-nix.lib.evalModule pkgs {
            projectRootFile = "flake.nix";
            programs.nixfmt.enable = true;
          };
        };
    in
    {
      # -- checks ------------------------------------------------------------
      checks = forAllSystems (pkgs: {
        # Pre-commit hooks run in a Nix derivation so CI can gate on them
        # without needing a mutable checkout.
        inherit ((perSystem pkgs)) pre-commit;

        # Treefmt formatting check (same treefmt config used by the formatter
        # below and the pre-commit hook above).
        formatting = (perSystem pkgs).treefmt-eval.config.build.check self;

        # Compose must pass the operator's explicit live-mode choice through to
        # the production backend. Parsing the YAML keeps this check static and
        # daemon-free while still validating the effective configuration key.
        docker-compose-live-luxmed =
          pkgs.runCommand "docker-compose-live-luxmed"
            {
              nativeBuildInputs = [ pkgs.yq-go ];
              src = self;
            }
            ''
              actual="$(yq -r '.services.backend.environment.LIVE_LUXMED_API' "$src/docker-compose.yml")"
              expected="$(printf '%s%s' '$' '{LIVE_LUXMED_API:?set LIVE_LUXMED_API=true}')"
              if [ "$actual" != "$expected" ]; then
                echo "backend LIVE_LUXMED_API must be required and propagated, got: $actual" >&2
                exit 1
              fi
              touch "$out"
            '';
      });

      # -- dev shell ---------------------------------------------------------
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          name = "lm-bot";

          packages = with pkgs; [
            # --- JVM side ---
            # Temurin to match the eclipse-temurin runtime image the backend
            # ships in, so local and container JVMs agree.
            temurin-bin-25
            # The nixpkgs sbt package is a *launcher*; the version that actually
            # builds this project is declared in project/build.properties
            # (sbt 2.x). Verified: this launcher starts sbt 2 correctly.
            sbt
            coursier

            # --- Scala tooling ---
            metals
            scalafmt

            # --- Frontend ---
            # Node 26+ is a hard requirement, not a preference. Gears' own README
            # warns that V8 in Node 24/25 stack-overflows in nested async
            # contexts, which Gears uses throughout. Pinning it here turns a
            # README note into a guarantee.
            nodejs_26

            # --- Scripting ---
            curl
            jq
            util-linux # uuidgen, for the spike's stable device identity
            git
          ];

          shellHook = (perSystem pkgs).pre-commit.shellHook + ''
            export JAVA_HOME="${pkgs.temurin-bin-25}"
            # Keep sbt's own heap modest; the compile-heavy work is in forked JVMs.
            export SBT_OPTS="''${SBT_OPTS:--Xmx2G -Xss4M}"

            # Database env vars (override via environment if using an external PG).
            # When running via `sbt startDev`, the forked JVM gets EMBEDDED_PG=true
            # from build.sbt's Compile / envVars, which starts an embedded PG.
            export DATABASE_URL="jdbc:postgresql://localhost:15432/lmbot"
            export DATABASE_USER="lmbot"
            export DATABASE_PASSWORD="lmbot"

            echo "lm-bot dev shell"
            echo "  jdk    $(java -version 2>&1 | head -1)"
            echo "  sbt    $(sbt --script-version 2>/dev/null || echo 'launcher present') (launches sbt 2 per project/build.properties)"
            echo "  node   $(node --version)"
            echo "  scala  3.8.4 (per build.sbt)"
            echo "  pg     embedded (auto-started by sbt startDev, or set DATABASE_URL for external)"
          '';
        };
      });

      # -- formatter (nixfmt via treefmt) ------------------------------------
      formatter = forAllSystems (pkgs: (perSystem pkgs).treefmt-eval.config.build.wrapper);
    };
}
