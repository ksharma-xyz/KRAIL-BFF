# CORS Configuration - Development vs Production

## ⚠️ IMPORTANT: Current Setup is DEVELOPMENT ONLY

The current CORS configuration in `server/src/main/kotlin/app/krail/bff/plugins/HTTP.kt` uses:

```kotlin
install(CORS) {
    anyHost()  // ⚠️ ALLOWS ALL ORIGINS - DEVELOPMENT ONLY!
    // ...
}
```

**This is ONLY safe for local development and testing!**

---

## 🔒 What You MUST Change Before Production

### Current (Development):

```kotlin
install(CORS) {
    anyHost()  // ⚠️ DANGEROUS in production!
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Post)
    // ... rest of config
}
```

### Required (Production):

```kotlin
install(CORS) {
    // Replace anyHost() with specific domains
    allowHost("yourdomain.com", schemes = listOf("https"))
    allowHost("app.yourdomain.com", schemes = listOf("https"))
    allowHost("api.yourdomain.com", schemes = listOf("https"))
    
    // ❌ DO NOT use anyHost() in production!
    
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Delete)
    allowMethod(HttpMethod.Options)
    
    allowHeader(HttpHeaders.ContentType)
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.Accept)
    allowHeader("X-Request-Id")
    
    exposeHeader(HttpHeaders.ContentType)
    exposeHeader("X-Request-Id")
    
    allowCredentials = true
    maxAgeInSeconds = 3600
}
```

---

## 🚨 Why anyHost() is Dangerous in Production

### What anyHost() Does:

Allows **ANY website** to make requests to your API:
- `https://evil-site.com` ✅ Allowed!
- `http://malicious.com` ✅ Allowed!
- `file://local-html-page` ✅ Allowed!

### Security Risks:

1. **Cross-Site Request Forgery (CSRF)**
   - Malicious websites can make authenticated requests on behalf of users
   - Example: User visits `evil-site.com` → It makes requests to your API with user's cookies

2. **Data Theft**
   - Malicious sites can read responses from your API
   - Example: Steal user data, trip history, personal information

3. **API Abuse**
   - Anyone can build a client for your API without permission
   - Increases load, costs, and potential for abuse

4. **Session Hijacking**
   - With `allowCredentials = true`, cookies are sent to ANY origin
   - Attackers can steal user sessions

---

## ✅ Production Checklist

Before deploying, verify:

- [ ] Removed `anyHost()` from CORS config
- [ ] Added only your specific domains with `allowHost()`
- [ ] Used HTTPS only (not HTTP) in production domains
- [ ] Tested that your frontend can still connect
- [ ] Tested that other origins are blocked
- [ ] Reviewed all allowed HTTP methods (remove unused ones)
- [ ] Reviewed all allowed headers (remove unused ones)

---

## 🧪 How to Test Production CORS

### Test That Your Domain Works:

```bash
curl -v -X OPTIONS https://your-api.com/api/v1/trip/plan \
  -H "Origin: https://yourdomain.com" \
  -H "Access-Control-Request-Method: GET"
```

**Should return:**
```
Access-Control-Allow-Origin: https://yourdomain.com
Access-Control-Allow-Methods: GET, POST, ...
```

### Test That Other Domains Are Blocked:

```bash
curl -v -X OPTIONS https://your-api.com/api/v1/trip/plan \
  -H "Origin: https://evil-site.com" \
  -H "Access-Control-Request-Method: GET"
```

**Should NOT return `Access-Control-Allow-Origin` header!**

---

## 📋 Example Production Configuration

```kotlin
fun Application.configureHTTP() {
    install(CORS) {
        // Production domains (HTTPS only)
        allowHost("krail.app", schemes = listOf("https"))
        allowHost("www.krail.app", schemes = listOf("https"))
        allowHost("api.krail.app", schemes = listOf("https"))
        
        // Staging environment (optional)
        if (environment.config.property("ktor.environment").getString() == "staging") {
            allowHost("staging.krail.app", schemes = listOf("https"))
        }
        
        // Only allow methods you actually use
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        // Remove PUT, DELETE, PATCH if not needed
        
        // Only allow headers you actually use
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        
        // Expose only necessary headers
        exposeHeader(HttpHeaders.ContentType)
        exposeHeader("X-Request-Id")
        
        // Keep credentials support if using cookies/auth
        allowCredentials = true
        
        // Cache preflight for 1 hour
        maxAgeInSeconds = 3600
    }
    
    // ... rest of configuration
}
```

---

## 🔧 Environment-Based Configuration

Better approach: Use environment variables for flexibility:

```kotlin
fun Application.configureHTTP() {
    install(CORS) {
        val env = environment.config.property("ktor.environment").getString()
        
        when (env) {
            "development" -> {
                // Local development
                anyHost()  // OK for local testing
            }
            "staging" -> {
                // Staging environment
                allowHost("staging.krail.app", schemes = listOf("https"))
                allowHost("localhost:3000", schemes = listOf("http"))
            }
            "production" -> {
                // Production - strict!
                allowHost("krail.app", schemes = listOf("https"))
                allowHost("www.krail.app", schemes = listOf("https"))
            }
        }
        
        // Common config for all environments
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        exposeHeader(HttpHeaders.ContentType)
        allowCredentials = true
        maxAgeInSeconds = 3600
    }
}
```

---

## 📚 Related Security Considerations

### 1. HTTPS Only in Production

Always use HTTPS in production:
- Prevents man-in-the-middle attacks
- Protects credentials and data in transit
- Required for modern browser features

### 2. API Keys / Authentication

CORS doesn't replace authentication:
- Still validate API keys
- Still check user permissions
- Still rate limit requests

### 3. Content Security Policy (CSP)

Consider adding CSP headers to your responses:
```kotlin
call.response.headers.append("Content-Security-Policy", "default-src 'self'")
```

---

## ⚠️ Summary

| Configuration | Development | Production |
|---------------|-------------|------------|
| `anyHost()` | ✅ OK | ❌ NEVER |
| Specific domains | ❌ Too restrictive | ✅ REQUIRED |
| HTTP origins | ✅ OK for localhost | ❌ HTTPS only |
| All methods | ✅ OK for testing | ❌ Only what's needed |

**Remember:** Change CORS config before deploying to production!

The current setup is **ONLY** for local development with the browser API tester.

---

## 📖 More Information

- [OWASP CORS Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/CORS_Cheat_Sheet.html)
- [MDN CORS Documentation](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)
- [Ktor CORS Plugin](https://ktor.io/docs/cors.html)

