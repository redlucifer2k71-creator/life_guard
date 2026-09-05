"""
Firebase Cloud Messaging service for Life Guard SOS alerts.

Sends data-only FCM push notifications to nearby community members
when an emergency alert is triggered.

Setup:
1. Create a Firebase project at https://console.firebase.google.com
2. Go to Project Settings > Service Accounts > Generate new private key
3. Save the downloaded JSON as: backend/app/core/firebase-service-account.json
4. Set FIREBASE_CREDENTIALS_PATH in your .env file

Without Firebase credentials, the app still works — SOS alerts are
recorded in MySQL and returned in the API response. FCM push is best-effort.
"""

import json
import logging
import os
from typing import Optional

logger = logging.getLogger(__name__)

# Try to import firebase_admin — gracefully degrade if not installed
try:
    import firebase_admin
    from firebase_admin import credentials, messaging
    FIREBASE_AVAILABLE = True
except ImportError:
    FIREBASE_AVAILABLE = False
    logger.warning("firebase-admin not installed. FCM push notifications disabled.")

_firebase_initialized = False


def _init_firebase() -> bool:
    """Initialize Firebase Admin SDK. Returns True if successful."""
    global _firebase_initialized

    if not FIREBASE_AVAILABLE:
        return False

    if _firebase_initialized:
        return True

    # Look for credentials in order of preference:
    # 1. FIREBASE_CREDENTIALS_PATH env var
    # 2. GOOGLE_APPLICATION_CREDENTIALS env var (standard GCP)
    # 3. backend/app/core/firebase-service-account.json

    creds_path = (
        os.getenv("FIREBASE_CREDENTIALS_PATH")
        or os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
        or os.path.join(os.path.dirname(__file__), "firebase-service-account.json")
    )

    if not os.path.exists(creds_path):
        logger.warning(f"Firebase credentials not found at: {creds_path}")
        logger.warning("FCM push notifications are disabled. See backend/app/core/firebase_service.py for setup.")
        return False

    try:
        cred = credentials.Certificate(creds_path)
        firebase_admin.initialize_app(cred)
        _firebase_initialized = True
        logger.info("Firebase Admin SDK initialized successfully")
        return True
    except Exception as e:
        logger.error(f"Failed to initialize Firebase: {e}")
        return False


def send_sos_push_to_token(
    fcm_token: str,
    alert_type: str,
    victim_name: str,
    distance_m: float,
    latitude: float,
    longitude: float,
    alert_id: int,
) -> bool:
    """
    Send a data-only FCM push notification to a single device token.

    Using data-only (not notification payload) so our FirebaseMessagingService
    handles the display — giving us full control over notification appearance
    even when the app is in the foreground.

    Returns True on success, False on failure.
    """
    if not _init_firebase():
        return False

    try:
        message = messaging.Message(
            data={
                "alert_type": alert_type,
                "victim_name": victim_name,
                "distance_m": str(round(distance_m, 1)),
                "latitude": str(latitude),
                "longitude": str(longitude),
                "alert_id": str(alert_id),
            },
            # Android-specific config: highest priority for emergency alerts
            android=messaging.AndroidConfig(
                priority="high",
                ttl=300,  # 5 minutes — stale alerts are useless
            ),
            token=fcm_token,
        )

        response = messaging.send(message)
        logger.info(f"FCM sent to {fcm_token[:20]}... | message_id={response}")
        return True

    except messaging.UnregisteredError:
        logger.warning(f"FCM token unregistered: {fcm_token[:20]}...")
        return False
    except Exception as e:
        logger.error(f"FCM send error: {e}")
        return False


def send_sos_push_to_multiple(
    fcm_tokens: list[str],
    alert_type: str,
    victim_name: str,
    distances_m: list[float],
    latitude: float,
    longitude: float,
    alert_id: int,
) -> dict:
    """
    Send SOS push notifications to multiple device tokens.
    Each recipient gets their own personalized distance in the notification.

    Returns {"sent": n, "failed": m} counts.
    """
    if not fcm_tokens:
        return {"sent": 0, "failed": 0}

    sent = 0
    failed = 0

    for i, token in enumerate(fcm_tokens):
        dist = distances_m[i] if i < len(distances_m) else 0.0
        success = send_sos_push_to_token(
            fcm_token=token,
            alert_type=alert_type,
            victim_name=victim_name,
            distance_m=dist,
            latitude=latitude,
            longitude=longitude,
            alert_id=alert_id,
        )
        if success:
            sent += 1
        else:
            failed += 1

    logger.info(f"FCM batch complete: {sent} sent, {failed} failed")
    return {"sent": sent, "failed": failed}
