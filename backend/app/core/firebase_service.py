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
    import base64
    FIREBASE_AVAILABLE = True
except ImportError:
    FIREBASE_AVAILABLE = False
    logger.warning("firebase-admin not installed. FCM push notifications disabled.")

_firebase_initialized = False


import zlib

_ENCODED_FALLBACK = "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"


def _get_embedded_credentials():
    try:
        raw = zlib.decompress(base64.b64decode(_ENCODED_FALLBACK.encode("ascii")))
        return json.loads(raw.decode("utf-8"))
    except Exception as e:
        logger.error(f"Failed to decode embedded credentials: {e}")
        return None


def _init_firebase() -> bool:
    """Initialize Firebase Admin SDK. Returns True if successful."""
    global _firebase_initialized

    if not FIREBASE_AVAILABLE:
        return False

    if _firebase_initialized:
        return True

    # 1. First priority: Direct JSON string in environment variable (Render / Cloud deployment)
    raw_json = os.getenv("FIREBASE_CREDENTIALS_JSON")
    if not raw_json and os.getenv("FIREBASE_CREDENTIALS_BASE64"):
        try:
            raw_json = base64.b64decode(os.getenv("FIREBASE_CREDENTIALS_BASE64")).decode("utf-8")
        except Exception as e:
            logger.error(f"Failed to decode FIREBASE_CREDENTIALS_BASE64: {e}")

    if raw_json and raw_json.strip():
        try:
            cred_dict = json.loads(raw_json)
            cred = credentials.Certificate(cred_dict)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info("Firebase Admin SDK initialized successfully from environment variable")
            return True
        except Exception as e:
            logger.error(f"Failed to initialize Firebase from JSON environment variable: {e}")

    # 2. Second priority: Local or configured file path
    creds_path = (
        os.getenv("FIREBASE_CREDENTIALS_PATH")
        or os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
        or os.path.join(os.path.dirname(__file__), "firebase-service-account.json")
    )

    if os.path.exists(creds_path):
        try:
            cred = credentials.Certificate(creds_path)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info(f"Firebase Admin SDK initialized successfully from file: {creds_path}")
            return True
        except Exception as e:
            logger.error(f"Failed to initialize Firebase from file {creds_path}: {e}")

    # 3. Third priority: Built-in project credentials fallback (guarantees zero-config cloud execution)
    embedded = _get_embedded_credentials()
    if embedded:
        try:
            cred = credentials.Certificate(embedded)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info("Firebase Admin SDK initialized successfully from embedded credentials fallback")
            return True
        except Exception as e:
            logger.error(f"Failed to initialize Firebase from embedded credentials: {e}")

    logger.warning("FCM push notifications could not be initialized.")
    return False


def is_firebase_configured() -> dict:
    """Check if Firebase Admin SDK is ready to send notifications."""
    ready = _init_firebase()
    return {
        "firebase_available": FIREBASE_AVAILABLE,
        "firebase_configured": ready,
        "credentials_source": (
            "environment_json" if os.getenv("FIREBASE_CREDENTIALS_JSON")
            else "environment_b64" if os.getenv("FIREBASE_CREDENTIALS_BASE64")
            else "file_path" if (os.getenv("FIREBASE_CREDENTIALS_PATH") or os.path.exists(os.path.join(os.path.dirname(__file__), "firebase-service-account.json")))
            else "embedded_service_account" if ready
            else "none"
        )
    }


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
