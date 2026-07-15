# Local Toolchain Provisioning (for agent / CI sessions)

This repo builds an Android app but sandboxed agent sessions usually start with **no
JDK, no Gradle, no Android SDK**, and sit **behind a Cloudflare "TLS-intercept" proxy**
that breaks normal HTTPS/cert validation. This guide is the exact, known-good recipe so
future sessions can build & test **without re-investigating** any of it.

> TL;DR: provision JDK 17 + Android SDK 34 **inside the workspace**, then build a custom
> Java truststore **from the LIVE proxy chain** (not from `proxy-ca.pem`), and point both
> Gradle (Java) and `git` (OpenSSL) at their respective CA material.

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

Everything below is installed **into the workspace** (never system dirs) so it is
disposable and already covered by `.gitignore`.

---

## 1. Environment variables (set these first, every session)

```bash
export WS="$(pwd)"                      # workspace / repo root
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$WS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$WS/.gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
# Java (Gradle) TLS: point at the custom truststore built in step 4
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=$WS/custom-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
```

All of `android-sdk/`, `.gradle-home/`, `.gradle-dist/`, `custom-cacerts`,
`proxy-ca.pem`, `git-proxy-ca.pem` are git-ignored — do **not** commit them.

---

## 2. JDK 17

If `/usr/lib/jvm/java-17-openjdk-amd64` is missing, install it:

```bash
apt-get update && apt-get install -y openjdk-17-jdk-headless
java -version   # expect: openjdk version "17.0.x"
```

---

## 3. Android SDK 34 (into the workspace)

```bash
mkdir -p "$ANDROID_HOME/cmdline-tools"
# Download commandline-tools (needs working TLS first — see step 4 if this fails)
# then unzip so the tools live at: $ANDROID_HOME/cmdline-tools/latest/bin
SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMGR" --sdk_root="$ANDROID_HOME" --licenses
"$SDKMGR" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Then pin the SDK path for Gradle (this file is git-ignored):

```bash
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$WS/local.properties"
```

Expected final layout: `android-sdk/{cmdline-tools/latest,platform-tools,platforms/android-34,build-tools/34.0.0,licenses}`

---

## 4. TLS: the important part (Cloudflare intercept)

The proxy re-signs **every** HTTPS connection with an internal CA:
`CN = Cloudflare TLS proxy-everything Intercept CA`.

**Critical gotcha:** the `proxy-ca.pem` file that ships in the sandbox does **NOT**
match the live intercept CA. Trusting it fails with `signature check failed` /
`unable to find valid certification path`. You must harvest the CA from the **live**
TLS handshake instead.

### 4a. Harvest the live CA chain (works for any host the build talks to)

```bash
# github.com, services.gradle.org, dl.google.com, repo.maven.apache.org all
# get re-signed by the same intercept CA, so one harvest is enough.
echo | openssl s_client -connect github.com:443 -servername github.com -showcerts 2>/dev/null \
  | awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/{print}' > "$WS/git-proxy-ca.pem"
# sanity: issuer should be the Cloudflare intercept CA
openssl x509 -in "$WS/git-proxy-ca.pem" -noout -issuer
```

### 4b. Build the Java truststore for Gradle (`custom-cacerts`)

Start from the JDK's bundled cacerts, then add the intercept CA:

```bash
cp "$JAVA_HOME/lib/security/cacerts" "$WS/custom-cacerts"
# import the intercept ROOT/CA cert (the issuer cert in the chain)
keytool -importcert -noprompt \
  -keystore "$WS/custom-cacerts" -storepass changeit \
  -alias cf-intercept -file "$WS/git-proxy-ca.pem"
keytool -list -keystore "$WS/custom-cacerts" -storepass changeit | grep -i entries
# expect ~122 entries incl. alias "cf-intercept"
```

`GRADLE_OPTS` (step 1) already points Gradle's JVM at this store.

### 4c. Make `git` trust the proxy (separate from Java!)

`git` uses OpenSSL, **not** the Java truststore, so it needs the PEM explicitly.
Config alone proved unreliable in-sandbox; the **env var** always works:

```bash
git config http.sslCAInfo "$WS/git-proxy-ca.pem"        # nice-to-have
GIT_SSL_CAINFO="$WS/git-proxy-ca.pem" git push origin HEAD   # the reliable way
```

> If a push still fails with `server certificate verification failed`, re-harvest
> `git-proxy-ca.pem` (step 4a) — the intercept CA can rotate between sessions.

---

## 5. Gradle 8.7

The wrapper downloads Gradle 8.7 automatically once TLS works:

```bash
./gradlew --version    # first run downloads the dist into .gradle-home / .gradle-dist
```

---

## 6. Build & test

```bash
./gradlew :app:compileDebugKotlin        # compile only
./gradlew :app:testDebugUnitTest         # unit tests
```

Results (JUnit XML) land in `app/build/test-results/testDebugUnitTest/`.

### Known-baseline test state (main + Issue E)

- **168 tests, 30 failures** is the expected GREEN-for-us baseline.
- The **30 failures are pre-existing** on `main` (biomechanics/validation drift,
  unrelated to engine refactors). See `docs/TEST_BASELINE.md` for the exact list.
- 4 test files have **pre-existing compile errors** (missing `kotlin.math` imports,
  unsupported 3-arg `max`): `ConstraintSolverTest`, `IKLimbHelperTest`,
  `TrunkFrameTest`, `VerticalPullPosesTest`. They are not caused by feature work.
  Do not "fix" them as part of unrelated tasks.

---

## 7. One-shot checklist for a fresh session

1. `export` the vars in step 1.
2. Ensure JDK 17 (step 2).
3. `openssl s_client ... > git-proxy-ca.pem` (step 4a).
4. Build `custom-cacerts` from it (step 4b).
5. Provision SDK 34 if `android-sdk/` is missing (step 3) + write `local.properties`.
6. `./gradlew :app:testDebugUnitTest` — expect 168 tests / 30 known failures.
7. For `git push`: prefix with `GIT_SSL_CAINFO="$WS/git-proxy-ca.pem"`.
