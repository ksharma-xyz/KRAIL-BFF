# KRAIL-BFF

Backend-for-Frontend service for the KRAIL mobile app, providing trip planning and transport information for the NSW transport network.

## 📚 Documentation

**Complete documentation is available in the [docs](docs/) folder.**

### Quick Links

- 🗺️ **[PLAN.md](PLAN.md)** - Master plan: what to work on, in what order (start here)
- 🚨 **[Emergency Runbook](docs/guides/EMERGENCY.md)** - Something's wrong in production: exact steps
- 🚀 **[Local Development Guide](docs/LOCAL_DEVELOPMENT.md)** - Get started with local development
- 🔐 **[Configuration Guide](docs/CONFIGURATION.md)** - Managing API keys and secrets
- 🧪 **[Testing Quick Start](docs/TESTING_QUICK_START.md)** - Quick API testing guide
- 🐛 **[Debugging Guide](docs/guides/DEBUGGING.md)** - Troubleshooting and debugging
- 📐 **[Modernization Plan](docs/reference/MODERNIZATION_PLAN.md)** - Project plan and migration roadmap

## ⚡ Quick Start

```bash
# 0. One-time per clone: enable the secret-guard pre-commit hook
#    (blocks commits containing API keys / tokens / private keys)
git config core.hooksPath .githooks

# 1. Copy the configuration template
cp local.properties.template local.properties

# 2. Edit local.properties and add your NSW Transport API key
# Get your key at: https://opendata.transport.nsw.gov.au/

# 3. Run the server (API key auto-loaded from local.properties)
./gradlew :server:run

# 4. Test the API

# Option A: Browser UI (Recommended!)
open docs/tools/api-tester.html

# Option B: Command line
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"
```

The server will start at `http://localhost:8080`

**🎨 Browser Testing:** Open `docs/tools/api-tester.html` for a beautiful interactive API tester!  
See [Browser API Tester Guide](docs/tools/README.md) for details.

> **Note:** The `local.properties` file is git-ignored and safe for storing API keys locally.

## 🐛 Debugging

**Quick endpoint test:**

```bash
./scripts/quick-debug.sh
```

This shows:
- ✅ All endpoints status (Protobuf + JSON)
- ✅ Size comparison (Protobuf vs JSON)
- ✅ Error handling verification

**Important:** Protobuf endpoints return **binary data** (not JSON). Browsers will show "gibberish" - this is correct! Your Android app will decode it perfectly.

### Android App: CLEARTEXT Error Fix

If you see: `CLEARTEXT communication to 10.0.2.2 not permitted by network security policy`

**Quick fix:** Add this to your Android app:

**File:** `app/src/main/res/xml/network_security_config.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

**File:** `app/src/main/AndroidManifest.xml`
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

**See full guide:** [ANDROID_NETWORK_SECURITY_FIX.md](ANDROID_NETWORK_SECURITY_FIX.md)

**Detailed troubleshooting:** [DEBUGGING_RESULTS.md](DEBUGGING_RESULTS.md)

## 🏗️ Project Structure

This project includes the following modules:

| Path             | Description                                             |
| ------------------|--------------------------------------------------------- |
| [server](server) | A runnable Ktor server implementation                   |
| [core](core)     | Domain objects and interfaces                           |
| [client](client) | Extensions for making requests to the server using Ktor |

## 🛠️ Building

To build the project, use one of the following tasks:

| Task                                            | Description                                                          |
| -------------------------------------------------|---------------------------------------------------------------------- |
| `./gradlew build`                               | Build everything                                                     |
| `./gradlew :server:buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `./gradlew :server:buildImage`                  | Build the docker image to use with the fat JAR                       |
| `./gradlew :server:publishImageToLocalRegistry` | Publish the docker image locally                                     |

## 🚀 Running

To run the project, use one of the following tasks:

| Task                          | Description                      |
| -------------------------------|---------------------------------- |
| `./gradlew :server:run`       | Run the server                   |
| `./gradlew :server:runDocker` | Run using the local docker image |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
