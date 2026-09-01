{
  description = "wf-market-watchdawg — a warframe.market watcher";

  # nixos-unstable, not a stable channel: the build pins a Java 25 toolchain and
  # Gradle 9.x, neither of which is in stable yet.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forAllSystems = f:
        nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAllSystems (pkgs: rec {
        default = market;
        market = pkgs.callPackage ./nix/market.nix { };
      });

      devShells = forAllSystems (pkgs:
        let
          # A real executable rather than a shellHook function, so it also works
          # under `nix develop --command mhelp`. Keep it in sync with the
          # function definitions in the shellHook below.
          mhelp = pkgs.writeShellScriptBin "mhelp" ''
            cat <<'EOF'
            wf-market-watchdawg dev shell

            build
              mg ARGS...     (cd market && ./gradlew ARGS...)   run any Gradle task
              mbuild         mg build                          compile + test
              mtest          mg test                           tests only
              mrun           mg bootRun                        start Tomcat on 8080

            database
              mpsql ARGS...  psql -h localhost -p 5432 -U watchdawg -d watchdawg
                                                               connect to the dev database
              mdb-reset      (cd market && docker compose down -v)
                                                               drop the volume, re-run migrations
              mdb-logs       (cd market && docker compose logs -f postgres)
              mflyway ARGS.. flyway -url=... -locations=filesystem:...db/migration ARGS...
                                                               run the Flyway CLI against the
                                                               dev container, e.g. mflyway info

            api
              bruno-run      (cd bruno && npx @usebruno/cli run \
                               --env production --delay 400 -r)
                                                               --delay 400 keeps under the 3 req/s limit

            not aliases — run these directly
              nix build .#market                     build the fat jar hermetically
              $(nix build --no-link --print-out-paths \
                  .#market.mitmCache.updateScript)
                                                     regenerate nix/deps.json after any
                                                     dependency change in build.gradle.kts;
                                                     run it from the repo root
            EOF
          '';
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk25          # build.gradle.kts pins JavaLanguageVersion.of(25)
              pkgs.docker-client  # bootRun (spring-boot-docker-compose) and Testcontainers
              pkgs.docker-compose # the `docker compose` v2 subcommand
              pkgs.postgresql_18  # psql client only; the server is the compose container
              pkgs.flyway         # same migrations as the app, driven without Gradle
              pkgs.nodejs_22      # npx, for the Bruno collection
              mhelp
            ];

            # Gradle's toolchain resolves against this instead of auto-downloading a JDK.
            JAVA_HOME = "${pkgs.jdk25}";

            # So mpsql does not prompt; these are the compose file's dev credentials.
            PGPASSWORD = "watchdawg";

            shellHook = ''
              REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
              export REPO_ROOT

              # The Gradle root is market/, not the repo root — these work from anywhere.
              mg()        { ( cd "$REPO_ROOT/market" && ./gradlew "$@" ); }
              mbuild()    { mg build "$@"; }
              mtest()     { mg test "$@"; }
              mrun()      { mg bootRun "$@"; }
              mpsql()     { psql -h localhost -p 5432 -U watchdawg -d watchdawg "$@"; }
              mdb-reset() { ( cd "$REPO_ROOT/market" && docker compose down -v ); }
              mdb-logs()  { ( cd "$REPO_ROOT/market" && docker compose logs -f postgres ); }
              # The app and this share one flyway_schema_history table, which is the point:
              # a migration applied here is seen as applied by bootRun, and vice versa.
              mflyway()   {
                ( cd "$REPO_ROOT/market" \
                  && flyway \
                       -url=jdbc:postgresql://localhost:5432/watchdawg \
                       -user=watchdawg \
                       -password=watchdawg \
                       -locations=filesystem:src/main/resources/db/migration \
                       "$@" )
              }
              bruno-run() {
                ( cd "$REPO_ROOT/bruno" \
                  && npx @usebruno/cli run --env production --delay 400 -r "$@" )
              }

              echo "jdk $(java -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')"
              docker info >/dev/null 2>&1 \
                || echo "warning: no docker daemon reachable — bootRun and tests will fail"
              echo "run 'mhelp' for available commands"
            '';
          };
        });
    };
}
