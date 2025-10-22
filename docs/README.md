# KRAIL-BFF Documentation

Welcome to the KRAIL-BFF documentation! This folder contains comprehensive guides for developing, testing, and deploying the backend-for-frontend service.

## 📚 Documentation Index

### Getting Started

- **[Local Development Guide](LOCAL_DEVELOPMENT.md)** - Complete setup guide for local development
  - Prerequisites and first-time setup
  - Running the server in different modes
  - Development workflow and testing
  - Configuration options
  - Common tasks and troubleshooting

- **[Testing Quick Start](TESTING_QUICK_START.md)** - Quick reference for API testing
  - Basic setup and configuration
  - Common test scenarios
  - Environment variables
  - Quick troubleshooting

### API Guides

- **[Trip Planning API](TRIP_PLANNING_API.md)** - Complete Trip Planning API reference
  - Endpoint documentation
  - Request/response examples
  - Transport mode IDs
  - Common stop IDs for testing
  - Error handling
  - Best practices

### Development

- **[Debugging Guide](DEBUGGING.md)** - Advanced debugging techniques
  - Logging configuration and filtering
  - Common issues and solutions
  - Debugging tools (IntelliJ, Charles Proxy, etc.)
  - Performance analysis
  - Network debugging

### Planning

- **[Roadmap](ROADMAP.md)** - Project roadmap and implementation plan
  - Completed features
  - Planned features
  - PR-by-PR implementation plan

## 🚀 Quick Start

New to the project? Start here:

1. **[Local Development Guide](LOCAL_DEVELOPMENT.md)** - Get your environment set up
2. **[Configuration Guide](CONFIGURATION.md)** - Set up your API keys securely
3. **[Testing Quick Start](TESTING_QUICK_START.md)** - Make your first API call
4. **[Trip Planning API](TRIP_PLANNING_API.md)** - Learn about the available endpoints

## 🔍 Common Tasks

### Running the Server Locally

```bash
# One-time setup: Copy and edit local.properties
cp local.properties.template local.properties
# Edit local.properties with your API key

# Run server (key auto-loaded)
./gradlew :server:run

# Server runs at http://localhost:8080
```

### Alternative: Using Environment Variables

```bash
# Set your API key
export NSW_API_KEY="your-api-key"

# Run with debug logging
LOG_LEVEL=DEBUG ./gradlew :server:run

# Server runs at http://localhost:8080
```

### Testing an API Endpoint

```bash
# Basic trip planning request
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328" | jq
```

### Viewing Logs

```bash
# All logs (JSON format)
./gradlew :server:run 2>&1 | grep '^{' | jq

# Only errors
./gradlew :server:run 2>&1 | grep '"level":"ERROR"' | jq
```

### Running Tests

```bash
# All tests
./gradlew test

# Specific test
./gradlew :server:test --tests "TripPlanningTest"
```

## 📖 Documentation Structure

```
docs/
├── README.md                    # This file - documentation index
├── LOCAL_DEVELOPMENT.md         # Setup and local development guide
├── TESTING_QUICK_START.md       # Quick API testing reference
├── TRIP_PLANNING_API.md         # Complete API documentation
├── DEBUGGING.md                 # Debugging and troubleshooting
├── ROADMAP.md                   # Project roadmap
└── openapi/
    └── documentation.yaml       # OpenAPI specification
```

## 🎯 By Role

### Developers

Start with these guides:
1. [Local Development Guide](LOCAL_DEVELOPMENT.md)
2. [Debugging Guide](DEBUGGING.md)
3. [Roadmap](ROADMAP.md)

### QA/Testers

Start with these guides:
1. [Testing Quick Start](TESTING_QUICK_START.md)
2. [Trip Planning API](TRIP_PLANNING_API.md)
3. [Debugging Guide](DEBUGGING.md) - For investigating issues

### DevOps/SRE

Focus on:
1. [Local Development Guide](LOCAL_DEVELOPMENT.md) - Configuration section
2. [Debugging Guide](DEBUGGING.md) - Performance and monitoring
3. Build and deployment sections in [Local Development Guide](LOCAL_DEVELOPMENT.md)

## 🛠️ Key Features

- **NSW Transport API Integration** - Trip planning across Sydney's transport network
- **Circuit Breaker Pattern** - Resilient API calls with automatic failure handling
- **Structured JSON Logging** - Easy log parsing and analysis
- **Metrics & Monitoring** - Dropwizard metrics for observability
- **Environment-Based Configuration** - Easy configuration via environment variables
- **Comprehensive Testing** - Unit and integration tests

## 🔗 External Resources

- [NSW Transport Open Data](https://opendata.transport.nsw.gov.au/) - Official API documentation
- [Ktor Documentation](https://ktor.io/docs/) - Ktor framework documentation
- [Kotlin Documentation](https://kotlinlang.org/docs/) - Kotlin language reference
- [Koin Documentation](https://insert-koin.io/) - Dependency injection framework

## 📝 Contributing

When adding new features or making changes:

1. Update relevant documentation
2. Add examples to the appropriate guide
3. Update the API documentation if endpoints change
4. Add troubleshooting notes for common issues

## ❓ Getting Help

Can't find what you're looking for?

1. **Check the guides** - Most topics are covered in the documentation
2. **Search issues** - Look for similar problems in the repository
3. **Enable DEBUG logging** - Get detailed information about what's happening
4. **Check external docs** - NSW Transport API and Ktor documentation

## 📅 Last Updated

October 22, 2025

---

**Happy Coding! 🚀**
