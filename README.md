# Life Guard — Automated Personal Safety & Community SOS Platform

Life Guard is an automated personal safety and emergency response ecosystem designed to protect lone commuters, late-night travelers, and vulnerable individuals. It combines native Android client features with an asynchronous FastAPI spatial backend and Firebase Cloud Messaging (FCM).

---

## 🌟 Key Features

* **Dead-Man's Switch "Guard Mode"**: Hold the central SOS button for 3 seconds when anticipating danger. Releasing it triggers a 30-second PIN challenge countdown. If the PIN is not entered within 30 seconds, an emergency SOS alert is automatically dispatched with live GPS coordinates.
* **Double-Tap SOS**: Rapid double-tap gesture on the SOS button to instantly fire an emergency alert.
* **Automated Safety Check-In Timers**: Schedule recurring check-in intervals (5m, 10m, 15m, 30m, 60m) that wake the device and prompt the user even when the phone is locked.
* **Route Deviation Guard**: Google Maps integration that tracks route progress and triggers a safety challenge if cross-track deviation exceeds 150 meters for more than 5 minutes.
* **Hyper-Local Spatial Dispatch**: MySQL Spatial queries (`ST_Distance_Sphere`) determine nearby registered helpers within a 500m–2km radius and dispatch push alerts via Firebase Cloud Messaging.
* **Dynamic Server Switcher**: Configure the backend server URL directly from the Android login screen (works with local Wi-Fi, emulators, or cloud endpoints).

---

## 🏗️ System Architecture

* **Android Client**: Native Kotlin, Jetpack Compose, Material3 Dark Theme, Foreground Location Service, AlarmManager, Retrofit2, Google Maps SDK, Firebase Messaging.
* **Backend API**: FastAPI (Python 3.10+), SQLAlchemy, Uvicorn.
* **Database**: MySQL 8.0 with OpenGIS Spatial Extensions.
* **Push Notifications**: Firebase Admin SDK (FCM).

---

## 🚀 Free Online Cloud Deployment (100% Free)

### 1. Database (Aiven.io MySQL - Free Tier)
1. Sign up at [Aiven.io](https://aiven.io) (Free forever, no credit card required).
2. Create a **MySQL 8.0** service on the Free plan.
3. Copy the **Service URI**:
   `mysql://avnadmin:PASSWORD@HOST:PORT/defaultdb?ssl-mode=REQUIRED`

### 2. Backend (Render.com - Free Web Service)
1. Fork or push this repository to GitHub.
2. Sign in to [Render.com](https://render.com) and click **New +** → **Web Service**.
3. Select your repository and configure:
   * **Root Directory**: `backend`
   * **Runtime**: `Python 3`
   * **Build Command**: `pip install -r requirements.txt`
   * **Start Command**: `uvicorn app.main:app --host 0.0.0.0 --port $PORT`
   * **Instance Type**: `Free`
4. Under **Environment Variables**, add:
   * `DATABASE_URL`: *(Your Aiven MySQL URI)*
5. Click **Deploy**. Render will generate a free HTTPS URL: `https://your-service.onrender.com`.

---

## 📱 Android Client Setup

1. Open the project in **Android Studio**.
2. Copy `android/app/google-services.json.example` to `android/app/google-services.json` and insert your Firebase configuration.
3. Add your Google Maps API key in `local.properties`:
   ```properties
   MAPS_API_KEY=AIzaSy...
   ```
4. Build and install:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
5. On the app login screen, tap **"⚙️ Server"** and paste your backend URL (`https://your-service.onrender.com` or local Wi-Fi IP `http://192.168.x.x:8000`).

---

## 🔒 Security & Privacy

* **Zero Hardcoded Secrets**: Credentials, service accounts, and API keys are strictly excluded via `.gitignore`.
* **Salted SHA-256 PIN Hashing**: All PIN verification is cryptographically salted on both client and server.
* **Offline Fallback**: Pre-alert countdown challenges can be cancelled locally without internet dependency.

---

## 📄 License
This project is licensed under the MIT License.
