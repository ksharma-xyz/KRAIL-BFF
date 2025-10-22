# Local Configuration Guide

This guide explains how to manage API keys and sensitive configuration locally without committing them to git.

## Quick Setup

### Step 1: Create Your Local Properties File

```bash
# Copy the template
cp local.properties.template local.properties

# Edit with your actual API key
nano local.properties  # or use your preferred editor
```

### Step 2: Add Your API Key

Edit `local.properties` and replace the placeholder:

```properties
# NSW Transport API Key
nsw.apiKey=YOUR_ACTUAL_API_KEY_HERE
```

### Step 3: Run the Server

```bash
# No need to export environment variables!
./gradlew :server:run
```

The API key will be automatically loaded from `local.properties`.

## Configuration Priority

The application loads configuration in this order (later sources override earlier ones):

1. **`application.yaml`** - Default values
2. **`local.properties`** - Your local overrides (git-ignored)
3. **Environment variables** - System environment
4. **Gradle properties** - Command line overrides

## local.properties Format

```properties
# NSW Transport API Configuration
nsw.apiKey=your-api-key-here

# Optional overrides
nsw.baseUrl=https://api.transport.nsw.gov.au
nsw.connectTimeoutMs=10000
nsw.readTimeoutMs=10000
nsw.breakerFailureThreshold=3
nsw.breakerResetTimeoutMs=60000
```

## Why Use local.properties?

✅ **Never committed to git** - `.gitignore` excludes it  
✅ **Convenient** - No need to export environment variables  
✅ **Team-friendly** - `local.properties.template` shows what's needed  
✅ **IDE-integrated** - Works seamlessly with IntelliJ IDEA  
✅ **Per-developer** - Each team member has their own keys  

## Alternative Methods

### Method 1: local.properties (Recommended for Development)

```bash
# One-time setup
cp local.properties.template local.properties
# Edit local.properties with your key

# Run server (key auto-loaded)
./gradlew :server:run
```

### Method 2: Environment Variables (Recommended for Production)

```bash
export NSW_API_KEY="your-api-key"
./gradlew :server:run
```

### Method 3: Command Line (Temporary Override)

```bash
./gradlew :server:run -DNSW_API_KEY="your-api-key"
```

## Team Setup

### For New Team Members

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd KRAIL-BFF
   ```

2. **Copy the template**
   ```bash
   cp local.properties.template local.properties
   ```

3. **Get API key**
   - Go to https://opendata.transport.nsw.gov.au/
   - Register/login
   - Generate API key

4. **Add to local.properties**
   ```properties
   nsw.apiKey=YOUR_KEY_HERE
   ```

5. **Run the server**
   ```bash
   ./gradlew :server:run
   ```

### For Project Maintainers

When adding new configuration keys:

1. Update `local.properties.template` with the new key
2. Update documentation
3. Add the key to `.env.example` if you have one
4. Notify team members to update their `local.properties`

## Security Best Practices

### ✅ DO

- ✅ Use `local.properties` for local development
- ✅ Keep `local.properties.template` updated
- ✅ Use environment variables in production
- ✅ Rotate API keys periodically
- ✅ Use different keys for dev/staging/production

### ❌ DON'T

- ❌ Commit `local.properties` to git
- ❌ Share your API keys in chat/email
- ❌ Hardcode keys in source code
- ❌ Use production keys for development
- ❌ Commit keys to public repositories

## Verification

### Check if local.properties is Loaded

```bash
# Run with debug logging
LOG_LEVEL=DEBUG ./gradlew :server:run 2>&1 | grep -i "api key"
```

You should NOT see the actual key in logs (it's not logged for security).

### Verify .gitignore

```bash
# This should show local.properties is ignored
git status

# This should return nothing (file is ignored)
git check-ignore local.properties
```

### Test Without local.properties

```bash
# Temporarily rename it
mv local.properties local.properties.backup

# Run server - should fail with "API key not set"
./gradlew :server:run

# Restore it
mv local.properties.backup local.properties
```

## Troubleshooting

### "API Key Not Found"

**Problem:** Server can't find your API key.

**Solution:**
```bash
# Check if file exists
ls -la local.properties

# Check if it has the right content
cat local.properties | grep nsw.apiKey

# Verify it's not empty
grep "nsw.apiKey=your-api-key-here" local.properties
# If this matches, you forgot to replace the placeholder!
```

### "Authentication Failed"

**Problem:** API key is invalid.

**Solution:**
1. Go to https://opendata.transport.nsw.gov.au/
2. Verify your API key is active
3. Copy the key again (watch for extra spaces)
4. Update `local.properties`

### "local.properties Committed to Git"

**Problem:** You accidentally committed the file.

**Solution:**
```bash
# Remove from git but keep local file
git rm --cached local.properties

# Commit the removal
git commit -m "Remove accidentally committed local.properties"

# Verify .gitignore includes it
grep "local.properties" .gitignore
```

## Environment Variables (Production)

For production deployments, use environment variables instead:

```bash
# Docker
docker run -e NSW_API_KEY="key" krail-bff

# Kubernetes
kubectl create secret generic nsw-api-key --from-literal=NSW_API_KEY="key"

# Heroku
heroku config:set NSW_API_KEY="key"

# AWS ECS
# Add to task definition environment variables
```

## CI/CD Integration

In your CI/CD pipeline, use secrets management:

```yaml
# GitHub Actions example
- name: Run tests
  env:
    NSW_API_KEY: ${{ secrets.NSW_API_KEY }}
  run: ./gradlew test
```

## Related Documentation

- [Local Development Guide](LOCAL_DEVELOPMENT.md) - Complete setup guide
- [Debugging Guide](DEBUGGING.md) - Troubleshooting configuration issues

