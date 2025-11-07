import app.krail.bff.proto.JourneyList
---
layout: default
title: Android Integration
parent: Guides
nav_order: 5
---

# Android Integration Guide
{: .no_toc }

Complete guide to integrate Protocol Buffers API in your Android app.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## ⚠️ Important: Fix CLEARTEXT Error First!

Before integrating, you MUST allow HTTP connections to `10.0.2.2` (emulator localhost).

**If you skip this, you'll get:** `CLEARTEXT communication to 10.0.2.2 not permitted`

### Quick Fix

**1. Create** `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

**2. Update** `app/src/main/AndroidManifest.xml`:

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

**3. Rebuild** your app:

```bash
./gradlew clean build
```

**⚠️ Security Note:** Remove this before production! Use HTTPS in production.

**Full guide:** [../../ANDROID_NETWORK_SECURITY_FIX.md](../../ANDROID_NETWORK_SECURITY_FIX.md)

---

## Overview

This guide shows how to integrate the KRAIL BFF Protocol Buffers API into your Android application using Square's Wire library.

**Benefits:**
- ✅ **83% smaller responses** (16 KB vs 96 KB)
- ✅ **Type-safe** Kotlin models
- ✅ **Ready-to-display** data (all formatting done server-side)
- ✅ **Faster parsing** than JSON

---

## Prerequisites

Your Android project should have:
- Kotlin support
- Wire library configured (or Protobuf Gradle plugin)
- Network library (Retrofit/Ktor/OkHttp)

---

## Step 1: Copy Proto File

Copy the proto file from the BFF server to your Android project:

**Source:** `server/src/main/proto/trip.proto`  
**Destination:** `app/src/main/proto/trip.proto` (or your proto directory)

```protobuf
syntax = "proto3";

package app.krail.bff.proto;

option java_package = "app.krail.bff.proto";
option java_multiple_files = true;

// Journey list response - contains all journey options
message JourneyList {
  repeated JourneyCardInfo journeys = 1;
}

// Journey Card Info - represents a single journey option
message JourneyCardInfo {
  string time_text = 1; // "in x mins"
  optional string platform_text = 2; // "Platform 1"
  optional string platform_number = 3; // "1", "2", "A" etc
  string origin_time = 4; // "11:30pm"
  string origin_utc_date_time = 5; // "2024-09-24T19:00:00Z"
  string destination_time = 6; // "11:40pm"
  string destination_utc_date_time = 7; // "2024-09-24T19:00:00Z"
  string travel_time = 8; // "10 mins"
  optional string total_walk_time = 9; // "5 mins"
  repeated TransportModeLine transport_mode_lines = 10;
  repeated Leg legs = 11;
  int32 total_unique_service_alerts = 12;
  optional DepartureDeviation departure_deviation = 13;
}

// Transport Mode Line
message TransportModeLine {
  string line_name = 1; // "T1", "L1" etc
  int32 transport_mode_type = 2; // Product class: 1=Train, 2=Metro, etc
}

// Leg - can be either walking or transport
message Leg {
  oneof leg_type {
    WalkingLeg walking_leg = 1;
    TransportLeg transport_leg = 2;
  }
}

// Walking Leg
message WalkingLeg {
  string duration = 1; // "10 mins"
}

// Transport Leg
message TransportLeg {
  TransportModeLine transport_mode_line = 1;
  optional string display_text = 2; // "towards Liverpool"
  string total_duration = 3; // "12 mins"
  repeated Stop stops = 4;
  optional WalkInterchange walk_interchange = 5;
  repeated ServiceAlert service_alert_list = 6;
  optional string trip_id = 7;
}

// Stop information
message Stop {
  string name = 1; // "Central Station, Platform 1"
  string time = 2; // "12:00pm"
  bool is_wheelchair_accessible = 3;
}

// Walk Interchange
message WalkInterchange {
  string duration = 1; // "5 mins"
  WalkPosition position = 2;
}

// Walk Position enum
enum WalkPosition {
  WALK_POSITION_UNSPECIFIED = 0;
  BEFORE = 1;
  AFTER = 2;
  IDEST = 3;
}

// Service Alert
message ServiceAlert {
  string id = 1;
  string subtitle = 2;
  string content = 3;
  string priority = 4;
  optional string url = 5;
}

// Departure Deviation
message DepartureDeviation {
  oneof deviation_type {
    string late = 1; // "5 mins late"
    string early = 2; // "2 mins early"
    bool on_time = 3; // true
  }
}
```

---

## Step 2: Configure Wire in build.gradle.kts

If not already configured, add Wire to your module's `build.gradle.kts`:

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
    
    // For Ktor client
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-android:3.0.0")
    implementation("io.ktor:ktor-client-logging:3.0.0")
}
```

---

## Step 3: Create API Client

### Using Ktor Client (Recommended)

**Option 1: Use the legacy Android-compatible endpoint** (if migrating existing app)

```kotlin
package xyz.ksharma.krail.trip.planner.network.client

import app.krail.bff.proto.JourneyList
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class TripPlannerApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://10.0.2.2:8080" // Android emulator localhost
) {
    
    suspend fun getTripPlan(
        origin: String,
        destination: String,
        depArrMacro: String = "dep",
        itdDate: String? = null,
        itdTime: String? = null,
        excludedMeans: String = "checkbox",
    ): Result<JourneyList> {
        return try {
            // Using legacy Android endpoint: /v1/tp/trip
            val response = httpClient.get("$baseUrl/v1/tp/trip") {
                parameter("name_origin", origin)
                parameter("name_destination", destination)
                parameter("depArrMacro", depArrMacro)
                parameter("type_destination", "any")
                parameter("calcNumberOfTrips", "6")
                parameter("type_origin", "any")
                parameter("TfNSWTR", "true")
                parameter("version", "10.2.1.42")
                parameter("coordOutputFormat", "EPSG:4326")
                parameter("itOptionsActive", "1")
                parameter("computeMonomodalTripBicycle", "false")
                parameter("cycleSpeed", "16")
                parameter("useElevationData", "1")
                parameter("outputFormat", "rapidJSON")
                parameter("excludedMeans", excludedMeans)
                itdDate?.let { parameter("itdDate", it) }
                itdTime?.let { parameter("itdTime", it) }
                
                // Request protobuf response
                accept(ContentType("application", "protobuf"))
            }
            
            // Decode protobuf binary
            val bytes = response.body<ByteArray>()
            val journeyList = JourneyList.ADAPTER.decode(bytes)
            
            Result.success(journeyList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

**Option 2: Use the new simplified endpoint** (recommended for new implementations)

```kotlin
package xyz.ksharma.krail.trip.planner.network.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class TripPlannerApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://10.0.2.2:8080" // Android emulator localhost
) {
    
    suspend fun getTripPlan(
        origin: String,
        destination: String,
        depArr: String = "dep",
        date: String? = null,
        time: String? = null,
        excludedModes: String? = null,
    ): Result<JourneyList> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/trip/plan-proto") {
                parameter("origin", origin)
                parameter("destination", destination)
                parameter("depArr", depArr)
                date?.let { parameter("date", it) }
                time?.let { parameter("time", it) }
                excludedModes?.let { parameter("excludedModes", it) }
                
                // Request protobuf response
                accept(ContentType("application", "protobuf"))
            }
            
            // Decode protobuf binary
            val bytes = response.body<ByteArray>()
            val journeyList = JourneyList.ADAPTER.decode(bytes)
            
            Result.success(journeyList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### Create HttpClient

```kotlin
package xyz.ksharma.krail.trip.planner.network.client

import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.logging.*

fun createHttpClient(): HttpClient {
    return HttpClient(Android) {
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
        
        expectSuccess = true
    }
}
```

---

## Step 4: Use in ViewModel/Repository

```kotlin
package xyz.ksharma.krail.trip.planner.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.krail.bff.proto.JourneyCardInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.ksharma.krail.trip.planner.network.client.TripPlannerApiClient

class TripPlannerViewModel(
    private val apiClient: TripPlannerApiClient
) : ViewModel() {
    
    private val _journeys = MutableStateFlow<List<JourneyCardInfo>>(emptyList())
    val journeys: StateFlow<List<JourneyCardInfo>> = _journeys
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    fun searchTrips(origin: String, destination: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
### CLEARTEXT communication not permitted

If you see: `CLEARTEXT communication to 10.0.2.2 not permitted by network security policy`

**Solution:** Add network security configuration for localhost testing.

Create `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Allow cleartext (HTTP) traffic for localhost/development -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

Then update `AndroidManifest.xml`:

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
    ...
</application>
```

**⚠️ Important:** Remove this configuration before deploying to production! Use HTTPS in production.

---

            apiClient.getTripPlan(origin, destination)
                .onSuccess { journeyList ->
                    _journeys.value = journeyList.journeys
                    _isLoading.value = false
                }
                .onFailure { exception ->
                    _error.value = exception.message
                    _isLoading.value = false
                }
        }
    }
}
```

---

## Step 5: Display in Composable

```kotlin
package xyz.ksharma.krail.trip.planner.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.krail.bff.proto.JourneyCardInfo

@Composable
fun TripPlannerScreen(
    viewModel: TripPlannerViewModel
) {
    val journeys by viewModel.journeys.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator()
        }
        
        error?.let { errorMessage ->
            Text(
                text = "Error: $errorMessage",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
        
        LazyColumn {
            items(journeys) { journey ->
                JourneyCard(journey)
            }
        }
    }
}

@Composable
fun JourneyCard(journey: JourneyCardInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Time badge
            Text(
                text = journey.time_text, // "in 5 mins"
                style = MaterialTheme.typography.labelMedium
            )
            
            // Origin time
            Text(
                text = journey.origin_time, // "11:30pm"
                style = MaterialTheme.typography.headlineMedium
            )
            
            // Transport mode lines
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                journey.transport_mode_lines.forEach { line ->
                    TransportBadge(line)
                }
            }
            
            // Travel time
            Text(
                text = "Travel time: ${journey.travel_time}",
                style = MaterialTheme.typography.bodyMedium
            )
            
            // Walking time (if any)
            journey.total_walk_time?.let { walkTime ->
                Text(
                    text = "Walking: $walkTime",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Platform info
            journey.platform_text?.let { platform ->
                Text(
                    text = platform,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Service alerts
            if (journey.total_unique_service_alerts > 0) {
                Text(
                    text = "${journey.total_unique_service_alerts} alerts",
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            // Departure deviation
            journey.departure_deviation?.let { deviation ->
                when {
                    deviation.on_time == true -> {
                        Text("On time", color = MaterialTheme.colorScheme.primary)
                    }
                    deviation.late != null -> {
                        Text(deviation.late, color = MaterialTheme.colorScheme.error)
                    }
                    deviation.early != null -> {
                        Text(deviation.early, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
```

---

## Step 6: Handle Errors

The BFF returns **JSON for errors**, so you need to handle both protobuf and JSON:

```kotlin
suspend fun getTripPlan(
    origin: String,
    destination: String,
): Result<JourneyList> {
    return try {
        val response = httpClient.get("$baseUrl/api/v1/trip/plan-proto") {
            parameter("origin", origin)
            parameter("destination", destination)
            accept(ContentType("application", "protobuf"))
        }
        
        when (response.status) {
            HttpStatusCode.OK -> {
                val bytes = response.body<ByteArray>()
                val journeyList = JourneyList.ADAPTER.decode(bytes)
                Result.success(journeyList)
            }
            else -> {
                // Error responses are JSON
                val errorBody = response.body<String>()
                Result.failure(Exception("API Error: $errorBody"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

---

## API Endpoints

| Endpoint | Format | Size | Description |
|----------|--------|------|-------------|
| `/api/v1/trip/plan` | JSON | 96 KB | Trip planning (JSON) |
| `/api/v1/trip/plan-proto` | **Protobuf** | **16 KB** | **Trip planning (Protobuf - 83% smaller!)** |

---

## Testing

### Test with Android Emulator

```kotlin
// Use 10.0.2.2 for Android emulator localhost
const val BASE_URL = "http://10.0.2.2:8080"
```

### Test with Real Device

```kotlin
// Use your computer's local IP
const val BASE_URL = "http://192.168.1.100:8080"
```

### Sample Request

```kotlin
viewModel.searchTrips(
    origin = "10101100",  // Central Station
    destination = "10101120"  // Circular Quay
)
```

---

## Size Comparison

**Response sizes for the same trip data:**

| Format | Size | Reduction |
|--------|------|-----------|
| JSON | 96 KB | - |
| **Protobuf** | **16 KB** | **83% smaller** |

**Benefits:**
- Faster downloads on mobile networks
- Less data usage for users
- Quicker parsing and UI rendering

---

## Complete Example Project Structure

```
app/
├── src/main/
│   ├── proto/
│   │   └── trip.proto
│   ├── kotlin/
│   │   └── xyz/ksharma/krail/
│   │       ├── network/
│   │       │   └── client/
│   │       │       ├── TripPlannerApiClient.kt
│   │       │       └── HttpClientFactory.kt
│   │       ├── ui/
│   │       │   ├── viewmodel/
│   │       │   │   └── TripPlannerViewModel.kt
│   │       │   └── screens/
│   │       │       └── TripPlannerScreen.kt
│   │       └── di/
│   │           └── NetworkModule.kt
│   └── AndroidManifest.xml
└── build.gradle.kts
```

---

## Troubleshooting

### Proto not generating classes

```bash
# Clean and rebuild
./gradlew clean
./gradlew generateProtos
./gradlew build
```

### Cannot connect to server

1. Make sure server is running: `./gradlew :server:run`
2. Use `10.0.2.2:8080` for emulator
3. Use your local IP for real device
4. Check firewall settings

### Protobuf parsing error

Make sure both server and Android use the **same proto file version**.

---

## Next Steps

- Add error handling UI
- Implement caching with Room database
- Add pull-to-refresh
- Show loading states
- Handle network errors gracefully

See the [BFF API documentation](https://ksharma-xyz.github.io/KRAIL-BFF/) for more details.

