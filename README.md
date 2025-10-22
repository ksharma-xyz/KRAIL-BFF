# KRAIL-BFF

Backend-for-Frontend service for the KRAIL mobile app, providing trip planning and transport information for the NSW transport network.

## 📚 Documentation

**Complete documentation is available in the [docs](docs/) folder.**

### Quick Links

- 🚀 **[Local Development Guide](docs/LOCAL_DEVELOPMENT.md)** - Get started with local development
- 🔐 **[Configuration Guide](docs/CONFIGURATION.md)** - Managing API keys and secrets
- 🧪 **[Testing Quick Start](docs/TESTING_QUICK_START.md)** - Quick API testing guide
- 📖 **[Trip Planning API](docs/TRIP_PLANNING_API.md)** - Complete API reference
- 🐛 **[Debugging Guide](docs/DEBUGGING.md)** - Troubleshooting and debugging
- 🗺️ **[Roadmap](docs/ROADMAP.md)** - Project roadmap

## ⚡ Quick Start

```bash
# 1. Copy the configuration template
cp local.properties.template local.properties

# 2. Edit local.properties and add your NSW Transport API key
# Get your key at: https://opendata.transport.nsw.gov.au/

# 3. Run the server (API key auto-loaded from local.properties)
./gradlew :server:run

# 4. Test the API
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"
```

The server will start at `http://localhost:8080`

> **Note:** The `local.properties` file is git-ignored and safe for storing API keys locally.

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
