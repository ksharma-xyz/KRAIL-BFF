# Configuration Guide

## NSW Transport API Key Configuration

The application requires a valid NSW Transport API key to function. You can obtain one from:
https://opendata.transport.nsw.gov.au/

### Configuration Priority (in order)

The application checks for the API key in the following order:

1. **Environment Variable** (recommended for servers/production)
   - `NSW_API_KEY=your-api-key-here`
   
2. **local.properties file** (recommended for local development)
   - `nsw.apiKey=your-api-key-here`
   
3. **application.yaml** (not recommended for secrets)
   - Only use this as a last resort or for non-sensitive defaults

### Local Development Setup

For local development, use the `local.properties` file:

```bash
# Copy the template
cp local.properties.template local.properties

# Edit local.properties and add your API key
# nsw.apiKey=your-actual-api-key-here
```

The `local.properties` file is:
- ✅ Git-ignored (safe for secrets)
- ✅ Only loaded when present (optional)
- ✅ Perfect for local development
- ❌ Should NOT be deployed to servers

### Server/Production Setup

For servers (staging, production), use environment variables:

```bash
# Set the environment variable
export NSW_API_KEY=your-api-key-here

# Or in your deployment configuration (Docker, Kubernetes, etc.)
# Docker:
docker run -e NSW_API_KEY=your-key ...

# Kubernetes:
# Add to your deployment.yaml as a secret/configmap
```

### Configuration Validation

The application will **fail fast at startup** if the API key is missing or empty. You'll see a clear error message explaining:
- Where it tried to find the key
- How to configure it based on your environment (local vs server)
- Links to get an API key

### How to Check Which Configuration Source is Being Used

Check the server logs at startup. You'll see:
```
✅ NSW API Key loaded successfully from: environment variable
```
or
```
✅ NSW API Key loaded successfully from: local.properties file
```
or
```
✅ NSW API Key loaded successfully from: application.yaml
```

### All Available Configuration Options

| Property | Environment Variable | local.properties | application.yaml | Default |
|----------|---------------------|------------------|------------------|---------|
| API Key | `NSW_API_KEY` | `nsw.apiKey` | `nsw.apiKey` | *(required)* |
| Base URL | `NSW_BASE_URL` | `nsw.baseUrl` | `nsw.baseUrl` | `https://api.transport.nsw.gov.au` |
| Connect Timeout | `NSW_CONNECT_TIMEOUT_MS` | `nsw.connectTimeoutMs` | `nsw.connectTimeoutMs` | `10000` |
| Read Timeout | `NSW_READ_TIMEOUT_MS` | `nsw.readTimeoutMs` | `nsw.readTimeoutMs` | `10000` |
| Breaker Threshold | `NSW_BREAKER_FAILURE_THRESHOLD` | `nsw.breakerFailureThreshold` | `nsw.breakerFailureThreshold` | `3` |
| Breaker Reset | `NSW_BREAKER_RESET_TIMEOUT_MS` | `nsw.breakerResetTimeoutMs` | `nsw.breakerResetTimeoutMs` | `60000` |

### Example Configurations

#### Local Development (local.properties)
```properties
nsw.apiKey=your-jwt-token-here
nsw.connectTimeoutMs=15000
nsw.readTimeoutMs=15000
```

#### Server (Environment Variables)
```bash
export NSW_API_KEY=your-jwt-token-here
export NSW_CONNECT_TIMEOUT_MS=15000
export NSW_READ_TIMEOUT_MS=15000
```

#### Docker Compose
```yaml
services:
  krail-bff:
    image: krail-bff:latest
    environment:
      - NSW_API_KEY=${NSW_API_KEY}
      - NSW_CONNECT_TIMEOUT_MS=15000
    ports:
      - "8080:8080"
```

### Troubleshooting

#### Error: "NSW API Key is missing"
- **Local Development**: Create `local.properties` and add `nsw.apiKey=your-key`
- **Server**: Set `NSW_API_KEY` environment variable

#### Error: "HTTP 401 Unauthorized"
- Your API key is invalid or expired
- Get a new API key from https://opendata.transport.nsw.gov.au/

#### How to verify your configuration without starting the server
```bash
# Check if environment variable is set
echo $NSW_API_KEY

# Check if local.properties exists
cat local.properties | grep nsw.apiKey
```

### Security Best Practices

✅ **DO:**
- Use environment variables on servers
- Use `local.properties` for local development
- Keep `local.properties` in `.gitignore`
- Rotate API keys regularly

❌ **DON'T:**
- Commit API keys to git
- Put API keys in `application.yaml` (it's version controlled)
- Share API keys in plain text
- Deploy `local.properties` to servers

