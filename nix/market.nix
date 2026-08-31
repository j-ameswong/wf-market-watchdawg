# The Spring Boot fat jar, built hermetically.
#
# Note this builds with nixpkgs' Gradle rather than market/gradlew: the wrapper
# would try to download its own distribution, which the build sandbox blocks.
{ lib, stdenv, gradle_9, jdk25, makeBinaryWrapper }:

let
  # gradle_9, not the default `gradle` attribute: that is still 8.14.4, which
  # cannot parse a Java 25 version and dies with the bare string "25.0.4.1".
  # 9.5.1 here vs 9.7.1 in market/gradlew — both 9.x, and the build script uses
  # no version-specific API.
  #
  # Gradle itself must also run on the JDK the build's toolchain pins, otherwise
  # it looks for a Java 25 it is not allowed to auto-download in the sandbox.
  gradle' = gradle_9.override { java = jdk25; };
in
stdenv.mkDerivation (finalAttrs: {
  pname = "market";
  version = "0.0.1-SNAPSHOT"; # keep in sync with market/build.gradle.kts

  src = ../market;

  nativeBuildInputs = [ gradle' makeBinaryWrapper ];

  # Dependency lock. Regenerate after any change to build.gradle.kts, from the
  # repo root (updateScript is a bare script file, not an app — `nix run` on it
  # fails with "Not a directory", and it writes to a relative nix/deps.json):
  #   $(nix build --no-link --print-out-paths .#market.mitmCache.updateScript)
  mitmCache = gradle'.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };
  __darwinAllowLocalNetworking = true; # the mitm proxy binds locally

  gradleFlags = [ "-Dorg.gradle.java.home=${jdk25}" ];

  # bootJar, not build: `build` runs the test task, and the tests need a live
  # Docker daemon for Testcontainers. Testing stays a dev-shell activity.
  gradleBuildTask = "bootJar";
  doCheck = false;

  # The developmentOnly deps (devtools, docker-compose) are excluded from
  # bootJar, so this jar will NOT start its own Postgres. Supply the datasource
  # via the environment, e.g. SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD.
  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/market
    cp build/libs/${finalAttrs.pname}-${finalAttrs.version}.jar \
      $out/share/market/market.jar

    makeWrapper ${jdk25}/bin/java $out/bin/market \
      --add-flags "-jar $out/share/market/market.jar"

    runHook postInstall
  '';

  meta = {
    description = "warframe.market watcher service";
    mainProgram = "market";
    platforms = lib.platforms.unix;
  };
})
