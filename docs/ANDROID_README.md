# Android Integration - Quick Start

## 📋 What You Need

Copy these files to your Android project:

### 1. Proto File
**File:** `trip.proto`  
**Location in Android:** `app/src/main/proto/trip.proto`

This file defines the Protocol Buffers schema. Wire will generate Kotlin classes from it.

---

## 🚀 Quick Integration Steps

### Step 1: Copy Proto File
```bash
# Copy trip.proto to your Android project
cp trip.proto <your-android-project>/app/src/main/proto/
```

### Step 2: Add Wire to build.gradle.kts
```kotlin
plugins {
    id("com.squareup.wire") version "5.1.0"
}

wire {
    kotlin {
        android = true
        javaInterop = true
    }
    sourcePath {
        srcDir("src/main/proto")
    }
}

dependencies {
    implementation("com.squareup.wire:wire-runtime:5.1.0")
}
```

### Step 3: Build Project
```bash
./gradlew build
```

This generates Kotlin classes in `build/generated/source/wire/`:
- `JourneyList`
- `JourneyCardInfo`
- `Leg`, `Stop`, `TransportModeLine`
- And more...

### Step 4: Create API Client
```kotlin
suspend fun getTripPlan(origin: String, destination: String): Result<JourneyList> {
    val response = httpClient.get("http://10.0.2.2:8080/api/v1/trip/plan-proto") {
        parameter("origin", origin)
        parameter("destination", destination)
        accept(ContentType("application", "protobuf"))
    }
    
    val bytes = response.body<ByteArray>()
    val journeyList = JourneyList.ADAPTER.decode(bytes)
    return Result.success(journeyList)
}
```

### Step 5: Display in UI
```kotlin
journeyList.journeys.forEach { journey ->
    Text(journey.time_text)        // "in 5 mins"
    Text(journey.origin_time)      // "11:30pm"
    Text(journey.travel_time)      // "10 mins"
    journey.platform_text?.let { Text(it) } // "Platform 1"
}
```

---

## 📱 Testing

### Test Stop IDs
- Central Station → Circular Quay: `origin=10101100&destination=10101120`
- Parramatta → Town Hall: `origin=215020&destination=200070`

### Expected Response
- Format: Binary Protocol Buffers
- Size: ~16 KB (vs 96 KB JSON)
- Content-Type: `application/protobuf`

---

## 🔗 Full Documentation

For complete guide with code examples, error handling, and UI samples:

**Online:** https://ksharma-xyz.github.io/KRAIL-BFF/guides/ANDROID_INTEGRATION.html

**Local:** See `guides/ANDROID_INTEGRATION.md`

---

## 📝 Agent Prompt

If using an AI agent for Android development, use the prompt in:

**File:** `ANDROID_INTEGRATION_PROMPT.md`

This provides a complete, ready-to-use prompt that guides the agent through:
1. Finding existing code
2. Adding proto file
3. Configuring Wire
4. Creating API client
5. Updating UI
6. Testing

---

## ⚡ Why Protocol Buffers?

| Metric | JSON | Protobuf | Savings |
|--------|------|----------|---------|
| Size | 96 KB | 16 KB | **83%** |
| Parse Time | ~50ms | ~15ms | **70%** |
| Type Safety | ❌ | ✅ | - |
| Format | Text | Binary | - |

**Result:** Faster app, less data usage, better UX! 🚀

---

## 🆘 Need Help?

1. Check the [full Android Integration guide](guides/ANDROID_INTEGRATION.md)
2. See [API documentation](api/TRIP_PLANNING_API.md)
3. Review [Error Handling](api/ERROR_HANDLING.md)

---

## 📦 Files in This Directory

- `trip.proto` - Protocol Buffers schema (copy to Android)
- `ANDROID_INTEGRATION_PROMPT.md` - Complete prompt for AI agent
- `guides/ANDROID_INTEGRATION.md` - Full integration guide
- `api/` - API reference documentation

