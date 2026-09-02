#!/usr/bin/env bash
#
# Turns a plain Debian/Ubuntu server into the machine that builds branded apps.
#
#   sudo tools/brand/setup-runner.sh --repo cubepy/CubeVPN --token AXXXXX…
#
# The token is a *runner registration* token, not a personal access token. Get it from
# Settings > Actions > Runners > New self-hosted runner on the repository; it expires in an
# hour, which is why it is an argument rather than something stored anywhere.
#
# Why a self-hosted runner at all: GitHub's own runners are billed by the minute on a private
# repository, and every brand is a separate build. On your own machine those minutes are free
# and unmetered. The part that matters more is that the signing keys never have to become
# GitHub secrets — they sit in a directory on this server that the runner reads directly, so
# a brand's key never leaves the machine that made it.
#
# Safe to run twice. Every step checks for its own result first.
#
set -euo pipefail

REPO=""
TOKEN=""
RUNNER_USER="cubevpn-runner"
LABELS="brand-builder"
BRANDS_DIR="/var/lib/cubevpn-brands"
SDK_DIR="/opt/android-sdk"
# Any recent build of the command-line tools works — sdkmanager updates itself and the package
# list comes from Google, not from this zip. Override if this URL ever goes stale.
CMDLINE_TOOLS_URL="${CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"

while [ $# -gt 0 ]; do
    case "$1" in
        --repo)   REPO="$2"; shift 2 ;;
        --token)  TOKEN="$2"; shift 2 ;;
        --user)   RUNNER_USER="$2"; shift 2 ;;
        --labels) LABELS="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

if [ "$(id -u)" != "0" ]; then
    echo "run this with sudo — it installs packages and a systemd service" >&2
    exit 2
fi
if [ -z "$REPO" ] || [ -z "$TOKEN" ]; then
    echo "usage: $0 --repo owner/name --token <runner registration token>" >&2
    exit 2
fi

say() { printf '\n==> %s\n' "$1"; }

# --- packages -------------------------------------------------------------------------------
# python3-pil is make_icons.py's Pillow; without it every build fails at the icon step rather
# than at setup, which is a much worse place to find out.
say "packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl unzip git openjdk-17-jdk-headless python3 python3-pil >/dev/null
echo "    jdk $(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/'), python $(python3 -V | cut -d' ' -f2)"

# --- the account the runner runs as ----------------------------------------------------------
# GitHub's runner refuses to run as root, and that refusal is correct: a workflow on this
# repository can run arbitrary commands as this user, so it gets its own account with nothing
# else on it.
say "user $RUNNER_USER"
if ! id "$RUNNER_USER" >/dev/null 2>&1; then
    useradd -m -s /bin/bash "$RUNNER_USER"
    echo "    created"
else
    echo "    already exists"
fi
RUNNER_HOME="$(getent passwd "$RUNNER_USER" | cut -d: -f6)"

# --- Android SDK ------------------------------------------------------------------------------
say "Android SDK"
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$SDK_DIR/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/tools.zip"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mv "$tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$tmp"
    echo "    command-line tools installed"
else
    echo "    command-line tools already present"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --install \
    "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null
chown -R "$RUNNER_USER":"$RUNNER_USER" "$SDK_DIR"
echo "    platform 36, build-tools 36.0.0"

cat > /etc/profile.d/android-sdk.sh <<EOF
export ANDROID_HOME=$SDK_DIR
export ANDROID_SDK_ROOT=$SDK_DIR
export PATH=\$PATH:$SDK_DIR/platform-tools
EOF

# --- where brands live ------------------------------------------------------------------------
# Deliberately outside any checkout. Brand files hold a reseller's details and the keystore
# directory holds keys that cannot be regenerated, so neither may depend on a clone that a
# workflow is free to delete and recreate.
say "brand directory"
mkdir -p "$BRANDS_DIR/keystores"
chown -R "$RUNNER_USER":"$RUNNER_USER" "$BRANDS_DIR"
chmod 700 "$BRANDS_DIR" "$BRANDS_DIR/keystores"
echo "    $BRANDS_DIR   (brand files here, keys in keystores/)"

# --- the runner itself --------------------------------------------------------------------------
say "GitHub runner"
RUNNER_DIR="$RUNNER_HOME/actions-runner"
if [ ! -f "$RUNNER_DIR/config.sh" ]; then
    VER="$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
           | grep -m1 '"tag_name"' | sed 's/.*"v\([^"]*\)".*/\1/')"
    [ -n "$VER" ] || { echo "could not determine the latest runner version" >&2; exit 1; }
    mkdir -p "$RUNNER_DIR"
    curl -fsSL "https://github.com/actions/runner/releases/download/v${VER}/actions-runner-linux-x64-${VER}.tar.gz" \
        | tar xz -C "$RUNNER_DIR"
    # The runner is a .NET application and needs ICU; which package provides it differs by
    # distribution, so use the script GitHub ships with it rather than guessing a name.
    if [ -x "$RUNNER_DIR/bin/installdependencies.sh" ]; then
        "$RUNNER_DIR/bin/installdependencies.sh" >/dev/null
    fi
    chown -R "$RUNNER_USER":"$RUNNER_USER" "$RUNNER_DIR"
    echo "    runner v$VER downloaded"
else
    echo "    runner already downloaded"
fi

if [ ! -f "$RUNNER_DIR/.runner" ]; then
    sudo -u "$RUNNER_USER" -H bash -c "cd '$RUNNER_DIR' && ./config.sh \
        --url 'https://github.com/$REPO' \
        --token '$TOKEN' \
        --name '$(hostname -s)-brand' \
        --labels '$LABELS' \
        --work _work --unattended --replace" >/dev/null
    echo "    registered with $REPO"
else
    echo "    already registered — remove $RUNNER_DIR/.runner to re-register"
fi

# svc.sh install fails on a second run rather than being a no-op, so ask systemd first.
if ! ls /etc/systemd/system/actions.runner.*.service >/dev/null 2>&1; then
    (cd "$RUNNER_DIR" && ./svc.sh install "$RUNNER_USER" >/dev/null)
    echo "    service installed"
fi
(cd "$RUNNER_DIR" && ./svc.sh start >/dev/null)
echo "    service running"

# --- optional publish hook -----------------------------------------------------------------------
# The workflow calls this if it exists, with <slug> <version> <dist dir>. It stays on the server
# rather than in the repository because where a build gets copied to is this machine's business,
# not the codebase's — and it means the web root's path never appears in a public workflow file.
if [ ! -f "$BRANDS_DIR/publish.sh" ]; then
    cat > "$BRANDS_DIR/publish.sh" <<'HOOK'
#!/usr/bin/env bash
# Called after a successful brand build:  publish.sh <slug> <version> <dist dir>
#
# Copy the APKs wherever this server serves downloads from, and write the update feed the
# app reads. Edit the two paths and remove the exit below.
set -euo pipefail
SLUG="$1"; VERSION="$2"; DIST="$3"

echo "publish.sh is not configured yet — built files are in $DIST"; exit 0

WEB_ROOT=/var/www/downloads
BASE_URL=https://example.invalid/downloads

mkdir -p "$WEB_ROOT/$SLUG"
cp "$DIST"/*.apk "$WEB_ROOT/$SLUG/"

cat > "$WEB_ROOT/$SLUG/update.json" <<JSON
{"version":"$VERSION",
 "url":"$BASE_URL/$SLUG/$SLUG-v$VERSION-universal-release.apk",
 "abis":{"arm64-v8a":"$BASE_URL/$SLUG/$SLUG-v$VERSION-arm64-v8a-release.apk",
         "armeabi-v7a":"$BASE_URL/$SLUG/$SLUG-v$VERSION-armeabi-v7a-release.apk"}}
JSON
HOOK
    chown "$RUNNER_USER":"$RUNNER_USER" "$BRANDS_DIR/publish.sh"
    chmod 750 "$BRANDS_DIR/publish.sh"
    echo "    wrote a publish hook stub at $BRANDS_DIR/publish.sh"
fi

cat <<EOF

Done. What is left is yours to fill in:

  1. Put each reseller's brand file and logo in $BRANDS_DIR
     (<slug>.properties, same format as tools/brand/brand.example.properties)
  2. Edit $BRANDS_DIR/publish.sh so builds land in your web root
  3. Back up $BRANDS_DIR/keystores/ somewhere off this server. A lost key
     means that brand can never ship an update again.

Then build from GitHub: Actions > Build brand > Run workflow, and give it the slug.
EOF
