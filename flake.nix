{
  description = "lm-bot — development environment (Scala 3 / sbt 2 / Scala.js Wasm / Postgres)";

  # Unstable, because two of this project's requirements are recent: nodejs_26
  # (Node 24/25 carry a V8 bug that breaks Gears' nested async contexts) and a
  # Scala toolchain new enough for Scala 3.8.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  # Deliberately no flake-parts here, unlike the nix-config flake: this project
  # exposes a single devShell and a formatter, so the module system would add an
  # input and a layer of indirection without buying anything. Revisit if
  # treefmt/git-hooks ever get folded in — that is where flake-parts earns its
  # keep.
  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          name = "lm-bot";

          packages = with pkgs; [
            # --- JVM side ---
            # Temurin to match the eclipse-temurin runtime image the backend
            # ships in, so local and container JVMs agree.
            temurin-bin-21
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

            # --- Database ---
            # For psql against the docker-compose Postgres. The server itself
            # runs in a container; this is just the client.
            postgresql_17

            # --- Containers ---
            # Deliberately NO docker-client here. The container runtime is a host
            # service, not something a devShell can provide, and shipping a
            # docker CLI would shadow the host's own (Podman, in this project's
            # case) and break Testcontainers rather than help it. See shellHook.

            # --- Spike + scripting (docs/superpowers/plans/*-2fa-spike.md) ---
            curl
            jq
            util-linux # uuidgen, for the spike's stable device identity
            git
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.temurin-bin-21}"
            # Keep sbt's own heap modest; the compile-heavy work is in forked JVMs.
            export SBT_OPTS="''${SBT_OPTS:--Xmx2G -Xss4M}"

            # Testcontainers (used by the backend integration tests) speaks the
            # Docker API and does not discover Podman's rootless socket on its
            # own. Point it at the socket if one is running and the user has not
            # already chosen a DOCKER_HOST.
            podman_sock="$XDG_RUNTIME_DIR/podman/podman.sock"
            if [ -z "''${DOCKER_HOST:-}" ] && [ -S "$podman_sock" ]; then
              export DOCKER_HOST="unix://$podman_sock"
              # Ryuk, Testcontainers' reaper sidecar, needs privileges that
              # rootless Podman will not grant, so it is disabled. Trade-off:
              # containers can outlive a hard-killed JVM. `podman ps` after a
              # crash, and prune if needed.
              export TESTCONTAINERS_RYUK_DISABLED=true
              # Containers that bind-mount the socket still expect the canonical
              # path inside themselves.
              export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
            fi

            echo "lm-bot dev shell"
            echo "  jdk    $(java -version 2>&1 | head -1)"
            echo "  sbt    $(sbt --script-version 2>/dev/null || echo 'launcher present') (launches sbt 2 per project/build.properties)"
            echo "  node   $(node --version)"
            echo "  scala  3.8.4 (per build.sbt)"

            if [ -n "''${DOCKER_HOST:-}" ]; then
              echo "  ctr    $DOCKER_HOST (ryuk disabled)"
            elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
              echo "  ctr    host docker"
            else
              echo
              echo "  ⚠ no container runtime reachable — Testcontainers tests will fail."
              echo "    Start one on the host: 'systemctl --user start podman.socket'"
              echo "    (or enable Docker: virtualisation.docker.enable = true)."
            fi
          '';
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}
