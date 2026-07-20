# Local Toolchain Provisioning (for agent / CI sessions)

This repo builds an Android app but sandboxed agent sessions usually start with **no
JDK, no Gradle, no Android SDK**, and sit **behind a Cloudflare "TLS-intercept" proxy**
that breaks normal HTTPS/cert validation. This guide is the exact, known-good recipe so
future sessions can build & test **without re-investigating** any of it.

> TL;DR: in this environment the whole toolchain is **pre-provisioned as a bundle under
> `/tmp/kilo`** (JDK 17, Gradle 8.7, Android SDK 34, and an already-built Java truststore).
> Point `JAVA_HOME` / `ANDROID_HOME` at it, set `GRADLE_OPTS` to the bundled
> `custom-cacerts`, and `./gradlew` works. No download, no harvest needed — unless the
> intercept CA rotates (then re-harvest, see §4).

---

## 0. Build matrix (what the project needs)

| Component      | Version / value                          | Source of truth                 |
| -------------- | ---------------------------------------- | ------------------------------- |
| JDK            | **17** (openjdk 17.0.x)                  | AGP 8.5 requirement             |
| Gradle         | **8.7** (`-all` dist)                    | `gradle/wrapper/*.properties`   |
| AGP            | 8.5.0                                    | `gradle/libs.versions.toml`     |
| Kotlin         | 2.0.0                                    | `gradle/libs.versions.toml`     |
| compileSdk     | 34                                       | `app/build.gradle`              |
| minSdk         | 24                                       | `app/build.gradle`              |
| targetSdk      | 28                                       | `app/build.gradle`              |
| build-tools    | 34.0.0                                   | required by compileSdk 34       |

---

## 1. The pre-provisioned bundle (this environment)

The toolchain is installed **under `/tmp/kilo`**, not system dirs. It contains:

```
/tmp/kilo/
  jdk-17.0.19+10/      # JDK 17 (use this as JAVA_HOME)
  gradle-8.7/          # Gradle 8.7 distribution (the wrapper downloads into .gradle-home)
  android-sdk/         # Android SDK 34: build-tools/34.0.0, platforms/android-34,
                       #   platform-tools, licenses
  custom-cacerts       # Java truststore with the Cloudflare intercept CA imported
  *.pem                # harvested leaf/CA certs for github.com, services.gradle.org,
                       #   dl.google.com, repo.maven.apache.org (fallback if CA rotates)
  gradle-8.7-bin.zip, jdk.tar.gz, cmdline-tools.zip  # source zips (not needed at runtime)
```

`/tmp/kilo` is **outside the repo**, so it is never committed and needs no `.gitignore`
handling. The Android SDK there already satisfies `compileSdk 34` / `build-tools 34.0.0`,
and no `local.properties` is required because the SDK is pointed at via `ANDROID_HOME`.

---

## 2. Environment variables (set these first, every session)

```bash
export WS="$(pwd)"                      # workspace / repo root
export JAVA_HOME=/tmp/kilo/jdk-17.0.19+10
export ANDROID_HOME=/tmp/kilo/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$WS/.gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
# Java (Gradle) TLS: point at the truststore that already ships in the bundle
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/tmp/kilo/custom-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
```

> NOTE: the RFC/AGENTS.md snippets reference `/usr/lib/jvm/java-17-openjdk-amd64` and a
> workspace-local `android-sdk` + `custom-cacerts`. Those paths are the *intended*
> provisioning target, but **this environment ships the bundle in `/tmp/kilo`** instead.
> Use the `/tmp/kilo` paths above — they are verified working. If `/tmp/kilo` is ever
> absent, fall back to the full provisioning recipe in §3–§5.

For `git` (separate trust path, OpenSSL not the Java store) the bundled PEMs work:

```bash
# the intercept CA can rotate between sessions; re-harvest if push fails (see §4a).
GIT_SSL_CAINFO=/tmp/kilo/github.com.pem git push origin HEAD
```

---

## 3. (Fallback only) Provision JDK 17 if `/tmp/kilo` is missing

```bash
apt-get update && apt-get install -y openjdk-17-jdk-headless
java -version   # expect: openjdk version "17.0.x"
```

---

## 4. TLS: the important part (Cloudflare intercept)

The proxy re-signs **every** HTTPS connection with an internal CA:
`CN = Cloudflare TLS proxy-everything Intercept CA`.

**Critical gotcha:** the `proxy-ca.pem` that may ship in the sandbox does **NOT** match
the live intercept CA. Trusting it fails with `signature check failed` /
`unable to find valid certification path`. You must use the bundle's `custom-cacerts`
(already built) or harvest the CA from the **live** TLS handshake.

### 4a. Harvest the live CA chain (only if `/tmp/kilo/custom-cacerts` is missing/rotated)

```bash
# github.com, services.gradle.org, dl.google.com, repo.maven.apache.org all get
# re-signed by the same intercept CA, so one harvest is enough.
echo | openssl s_client -connect github.com:443 -servername github.com -showcerts 2>/dev/null \
  | awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/{print}' > "$WS/git-proxy-ca.pem"
openssl x509 -in "$WS/git-proxy-ca.pem" -noout -issuer   # expect the Cloudflare intercept CA
```

### 4b. (Re)build the Java truststore for Gradle (`custom-cacerts`)

Start from the JDK's bundled cacerts, then add the intercept CA:

```bash
cp "$JAVA_HOME/lib/security/cacerts" "$WS/custom-cacerts"
keytool -importcert -noprompt \
  -keystore "$WS/custom-cacerts" -storepass changeit \
  -alias cf-intercept -file "$WS/git-proxy-ca.pem"
# expect ~122 entries incl. alias "cf-intercept"
```

`GRADLE_OPTS` (§2) points Gradle's JVM at this store (adjust the path if you built it
in `$WS` instead of `/tmp/kilo`).

### 4c. Make `git` trust the proxy (separate from Java!)

`git` uses OpenSSL, **not** the Java truststore, so it needs the PEM explicitly:

```bash
git config http.sslCAInfo "$WS/git-proxy-ca.pem"          # nice-to-have
GIT_SSL_CAINFO="$WS/git-proxy-ca.pem" git push origin HEAD # the reliable way
```

---

## 5. (Fallback only) Android SDK 34 + Gradle 8.7

If `/tmp/kilo/android-sdk` is missing, provision it (needs working TLS first):

```bash
mkdir -p "$ANDROID_HOME/cmdline-tools"
# download commandline-tools so they live at: $ANDROID_HOME/cmdline-tools/latest/bin
SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMGR" --sdk_root="$ANDROID_HOME" --licenses
"$SDKMGR" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$WS/local.properties"
```

The Gradle wrapper downloads 8.7 automatically once TLS works: `./gradlew --version`.

---

## 6. Build & test

```bash
./gradlew :app:compileDebugKotlin        # compile only
./gradlew :app:testDebugUnitTest         # unit tests
```

Results (JUnit XML) land in `app/build/test-results/testDebugUnitTest/`.

### Known-baseline test state (stabilization, post-S1)

- **236 tests executed** is the truthful baseline (the four previously-non-compiling
  files — `ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`,
  `VerticalPullPosesTest` — now compile and count). See `docs/TEST_BASELINE.md`.
- S1 (ConstraintSolver + IK) engine subsystems are **green**: `ConstraintSolverTest`
  (6/6), `IKLimbHelperTest`, `TrunkFrameTest` all pass. the MonkEngine runtime BONE_LENGTH
  (NECK_END→HEAD_POS) defect is fixed.
- Remaining failures (~24) are Validator (S2) / pose-authoring (S3) concerns per
  `docs/HISTORICAL/RFC_ENGINE_STABILIZATION.md` — not engine defects. Re-measure after each subsystem.

---

## 7. One-shot checklist for a fresh session

1. `export` the vars in §2 (the `/tmp/kilo` paths — these are verified working).
2. Confirm `JAVA_HOME/bin/java -version` prints 17.0.x.
3. Confirm `ANDROID_HOME` has `platforms/android-34` + `build-tools/34.0.0`.
4. `./gradlew :app:testDebugUnitTest` — expect 236 tests; S1 engine tests green.
5. For `git push`: prefix with `GIT_SSL_CAINFO=/tmp/kilo/github.com.pem`.
6. Only if TLS fails: re-harvest the live CA (§4a) and rebuild `custom-cacerts` (§4b).
