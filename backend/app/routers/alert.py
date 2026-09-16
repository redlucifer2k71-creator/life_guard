import hashlib
import logging
from datetime import datetime
from typing import List
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy import text

from app.core.database import get_db
from app.core.firebase_service import send_sos_push_to_multiple
from app.models.user import User
from app.schemas.alert import (
    AlertTriggerRequest,
    AlertTriggerResponse,
    AlertResolveRequest,
    AlertResolveResponse,
    NotifiedRecipient,
    AlertStatusEnum,
)

router = APIRouter(prefix="/api/v1/alerts", tags=["Emergency SOS Alerts"])
logger = logging.getLogger(__name__)


def hash_pin(pin: str) -> str:
    """Hash a numeric PIN securely using SHA-256 with salt."""
    salt = "lifeguard_pin_salt_2026"
    return hashlib.sha256((pin + salt).encode("utf-8")).hexdigest()


@router.post("/trigger", response_model=AlertTriggerResponse, status_code=status.HTTP_201_CREATED)
def trigger_alert(payload: AlertTriggerRequest, db: Session = Depends(get_db)):
    """Trigger an emergency SOS alert, insert spatial alert record, and query nearby community members."""
    try:
        # Verify user exists (auto-create fallback so alert never fails after container reboot)
        victim = db.query(User).filter(User.id == payload.user_id).first()
        if not victim:
            victim = User(
                id=payload.user_id,
                full_name=f"Protected User #{payload.user_id}",
                phone_number=f"user_{payload.user_id}",
                pin_hash="",
                is_active=True,
            )
            db.add(victim)
            db.commit()
            db.refresh(victim)

        # 1. Resolve effective victim location (use last known location if payload coordinates are (0,0))
        v_lat = payload.latitude
        v_lng = payload.longitude
        if abs(v_lat) < 0.0001 and abs(v_lng) < 0.0001:
            last_loc = db.execute(
                text("SELECT latitude, longitude FROM user_locations WHERE user_id = :uid"),
                {"uid": payload.user_id},
            ).mappings().first()
            if last_loc and (abs(float(last_loc["latitude"])) > 0.0001 or abs(float(last_loc["longitude"])) > 0.0001):
                v_lat = float(last_loc["latitude"])
                v_lng = float(last_loc["longitude"])
                logger.info(f"Victim {payload.user_id} cached location fallback: ({v_lat}, {v_lng})")

        is_sqlite = db.bind.dialect.name == "sqlite"
        from app.routers.location import haversine_distance_meters

        if is_sqlite:
            result = db.execute(
                text("""
                    INSERT INTO alerts (user_id, alert_type, status, latitude, longitude, triggered_at)
                    VALUES (:user_id, :alert_type, 'ACTIVE', :latitude, :longitude, datetime('now'));
                """),
                {
                    "user_id": payload.user_id,
                    "alert_type": payload.alert_type.value,
                    "latitude": v_lat,
                    "longitude": v_lng,
                },
            )
            db.commit()
            alert_id = result.lastrowid
        else:
            wkt_point = f"POINT({v_lng} {v_lat})"
            insert_alert_query = text("""
                INSERT INTO alerts (user_id, alert_type, status, latitude, longitude, location_point, triggered_at)
                VALUES (
                    :user_id, 
                    :alert_type, 
                    'ACTIVE', 
                    :latitude, 
                    :longitude, 
                    ST_SRID(ST_PointFromText(:wkt_point), 4326), 
                    NOW()
                );
            """)
            result = db.execute(
                insert_alert_query,
                {
                    "user_id": payload.user_id,
                    "alert_type": payload.alert_type.value,
                    "latitude": v_lat,
                    "longitude": v_lng,
                    "wkt_point": wkt_point,
                },
            )
            db.commit()
            alert_id = result.lastrowid

        # 2. Query ALL active registered community helpers and any active location reports
        rows = db.execute(
            text("""
                SELECT 
                    COALESCE(u.id, ul.user_id) AS user_id,
                    COALESCE(u.full_name, 'Community Member #' || ul.user_id) AS full_name,
                    COALESCE(u.phone_number, 'Unknown') AS phone_number,
                    u.fcm_token,
                    ul.latitude,
                    ul.longitude
                FROM user_locations ul
                LEFT JOIN users u ON ul.user_id = u.id
                WHERE (u.id IS NULL OR (u.id != :victim_id AND u.is_active = 1))
                  AND ul.user_id != :victim_id
                UNION
                SELECT 
                    u.id AS user_id,
                    u.full_name,
                    u.phone_number,
                    u.fcm_token,
                    NULL AS latitude,
                    NULL AS longitude
                FROM users u
                WHERE u.id != :victim_id
                  AND u.is_active = 1
                  AND u.id NOT IN (SELECT user_id FROM user_locations);
            """),
            {"victim_id": payload.user_id},
        ).mappings().all()

        nearby_candidates = []
        for r in rows:
            r_dict = dict(r)
            r_lat = r["latitude"]
            r_lng = r["longitude"]

            if r_lat is not None and r_lng is not None and (abs(float(r_lat)) > 0.0001 or abs(float(r_lng)) > 0.0001) and (abs(v_lat) > 0.0001 or abs(v_lng) > 0.0001):
                dist = haversine_distance_meters(v_lat, v_lng, float(r_lat), float(r_lng))
            else:
                # Proximity unknown / indoor emergency fix: mark as immediate community candidate
                dist = 25.0

            r_dict["distance_meters"] = dist
            nearby_candidates.append(r_dict)

        # Primary filter: within requested radius (default 5000m / 5km)
        effective_radius = max(payload.radius_meters, 5000.0)
        nearby_rows = [c for c in nearby_candidates if c["distance_meters"] <= effective_radius]

        # Safety Fallback: If 0 helpers within radius, expand up to 25,000m (25km) or any registered helper
        if not nearby_rows and nearby_candidates:
            nearby_rows = [c for c in nearby_candidates if c["distance_meters"] <= 25000.0]
            if not nearby_rows:
                nearby_candidates.sort(key=lambda x: x["distance_meters"])
                nearby_rows = nearby_candidates[:5]

        nearby_rows.sort(key=lambda x: x["distance_meters"])

        recipients: List[NotifiedRecipient] = []
        for row in nearby_rows:
            dist = round(float(row["distance_meters"]), 2)
            recipients.append(
                NotifiedRecipient(
                    user_id=row["user_id"],
                    full_name=row["full_name"],
                    phone_number=row["phone_number"],
                    fcm_token=row["fcm_token"],
                    distance_meters=dist,
                )
            )

            # Log recipient in alert_recipients table
            time_fn = "datetime('now')" if is_sqlite else "NOW()"
            log_recipient_query = text(f"""
                INSERT INTO alert_recipients (alert_id, recipient_user_id, distance_meters, delivery_status, notified_at)
                VALUES (:alert_id, :recipient_user_id, :distance_meters, 'SENT', {time_fn});
            """)
            db.execute(
                log_recipient_query,
                {
                    "alert_id": alert_id,
                    "recipient_user_id": row["user_id"],
                    "distance_meters": dist,
                },
            )

        db.commit()

        # Multi-device fallback: If 0 other users found, but victim has other active devices
        # (e.g. user logged into 2 phones with the same account during testing)
        sender_token = (payload.sender_fcm_token or "").strip()
        victim_other_tokens = [
            t.strip() for t in (victim.fcm_token or "").split(",")
            if t.strip() and t.strip() != sender_token
        ]
        if not recipients and victim_other_tokens:
            recipients.append(
                NotifiedRecipient(
                    user_id=victim.id,
                    full_name=f"{victim.full_name} (Secondary Device)",
                    phone_number=victim.phone_number,
                    fcm_token=",".join(victim_other_tokens),
                    distance_meters=0.0,
                )
            )

        # ── Send FCM push notifications to all recipients and registered devices ──
        fcm_destinations: List[tuple[str, float]] = []  # (token, distance_m)

        # 1. Add tokens from nearby community recipients (unpack comma-separated tokens)
        for r in recipients:
            if r.fcm_token:
                for tok in r.fcm_token.split(","):
                    tok = tok.strip()
                    if tok and tok != sender_token and tok not in [d[0] for d in fcm_destinations]:
                        fcm_destinations.append((tok, r.distance_meters))

        # 2. Multi-device support: also notify other active devices of the victim
        if victim.fcm_token:
            for tok in victim.fcm_token.split(","):
                tok = tok.strip()
                if tok and tok != sender_token and tok not in [d[0] for d in fcm_destinations]:
                    fcm_destinations.append((tok, 0.0))

        if fcm_destinations:
            tokens_to_send = [d[0] for d in fcm_destinations]
            distances_to_send = [d[1] for d in fcm_destinations]
            fcm_result = send_sos_push_to_multiple(
                fcm_tokens=tokens_to_send,
                alert_type=payload.alert_type.value,
                victim_name=victim.full_name,
                distances_m=distances_to_send,
                latitude=v_lat,
                longitude=v_lng,
                alert_id=alert_id,
            )
            logger.info(
                f"FCM push: {fcm_result['sent']} sent, {fcm_result['failed']} failed "
                f"out of {len(fcm_destinations)} device tokens"
            )

        return AlertTriggerResponse(
            status="success",
            alert_id=alert_id,
            alert_type=payload.alert_type,
            alert_status=AlertStatusEnum.ACTIVE,
            triggered_at=datetime.utcnow(),
            latitude=v_lat,
            longitude=v_lng,
            total_recipients_notified=len(recipients),
            recipients=recipients,
        )

    except HTTPException:
        raise
    except Exception as e:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to trigger emergency alert: {str(e)}",
        )


@router.post("/resolve", response_model=AlertResolveResponse)
def resolve_alert(payload: AlertResolveRequest, db: Session = Depends(get_db)):
    """Resolve/cancel an active emergency alert using victim PIN verification."""
    try:
        user = db.query(User).filter(User.id == payload.user_id).first()
        if not user:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"User with ID {payload.user_id} not found.",
            )

        # Verify PIN hash
        provided_hash = hash_pin(payload.pin)
        if provided_hash != user.pin_hash:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid PIN provided. Alert remains active!",
            )

        # Check alert status
        alert_row = db.execute(
            text("SELECT id, status FROM alerts WHERE id = :alert_id AND user_id = :user_id"),
            {"alert_id": payload.alert_id, "user_id": payload.user_id},
        ).mappings().first()

        if not alert_row:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Active alert with ID {payload.alert_id} for user {payload.user_id} not found.",
            )

        # Update alert status to CANCELLED_BY_PIN
        time_fn = "datetime('now')" if db.bind.dialect.name == "sqlite" else "NOW()"
        update_query = text(f"""
            UPDATE alerts 
            SET status = 'CANCELLED_BY_PIN', resolved_at = {time_fn} 
            WHERE id = :alert_id;
        """)
        db.execute(update_query, {"alert_id": payload.alert_id})
        db.commit()

        return AlertResolveResponse(
            status="success",
            message="Alert successfully cancelled via PIN verification.",
            alert_id=payload.alert_id,
            resolved_at=datetime.utcnow(),
        )

    except HTTPException:
        raise
    except Exception as e:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to resolve alert: {str(e)}",
        )


@router.get("/fcm-status")
def get_fcm_status():
    """Diagnostic endpoint to verify Firebase Admin SDK initialization and cloud configuration."""
    from app.core.firebase_service import is_firebase_configured
    return is_firebase_configured()


@router.get("/debug-status")
def get_debug_status(db: Session = Depends(get_db)):
    """Diagnostic endpoint to inspect live users, locations, and alerts."""
    users = db.execute(text("SELECT id, full_name, phone_number, fcm_token, is_active FROM users")).mappings().all()
    locations = db.execute(text("SELECT user_id, latitude, longitude, updated_at FROM user_locations")).mappings().all()
    alerts = db.execute(text("SELECT id, user_id, alert_type, latitude, longitude, triggered_at FROM alerts ORDER BY id DESC LIMIT 5")).mappings().all()
    from app.core.firebase_service import is_firebase_configured
    return {
        "firebase": is_firebase_configured(),
        "total_users": len(users),
        "users": [
            {
                "id": u["id"],
                "full_name": u["full_name"],
                "phone_number": u["phone_number"],
                "has_fcm": bool(u["fcm_token"]),
                "tokens_count": len([t for t in (u["fcm_token"] or "").split(",") if t.strip()]),
                "is_active": bool(u["is_active"]),
            }
            for u in users
        ],
        "locations": [dict(l) for l in locations],
        "recent_alerts": [dict(a) for a in alerts],
    }

