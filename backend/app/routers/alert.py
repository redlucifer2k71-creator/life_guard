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
        # Verify user exists
        victim = db.query(User).filter(User.id == payload.user_id, User.is_active == True).first()
        if not victim:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Active user with ID {payload.user_id} not found.",
            )

        wkt_point = f"POINT({payload.longitude} {payload.latitude})"

        # Insert Alert into alerts table
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
                "latitude": payload.latitude,
                "longitude": payload.longitude,
                "wkt_point": wkt_point,
            },
        )
        db.commit()

        # Get generated alert_id
        alert_id = result.lastrowid

        # Perform MySQL spatial query to locate nearby users within radius (100m to 1000m)
        spatial_query = text("""
            SELECT 
                u.id AS user_id,
                u.full_name,
                u.phone_number,
                u.fcm_token,
                ST_Distance_Sphere(ul.location_point, ST_SRID(ST_PointFromText(:wkt_point), 4326)) AS distance_meters
            FROM user_locations ul
            JOIN users u ON ul.user_id = u.id
            WHERE u.id != :victim_id
              AND u.is_active = 1
              AND ST_Distance_Sphere(ul.location_point, ST_SRID(ST_PointFromText(:wkt_point), 4326)) <= :radius_meters
            ORDER BY distance_meters ASC;
        """)

        nearby_rows = db.execute(
            spatial_query,
            {
                "victim_id": payload.user_id,
                "wkt_point": wkt_point,
                "radius_meters": payload.radius_meters,
            },
        ).mappings().all()

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
            log_recipient_query = text("""
                INSERT INTO alert_recipients (alert_id, recipient_user_id, distance_meters, delivery_status, notified_at)
                VALUES (:alert_id, :recipient_user_id, :distance_meters, 'SENT', NOW());
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

        # ── Send FCM push notifications to all nearby recipients ──
        # Best-effort: we don't fail the request if FCM has issues
        tokens_with_fcm = [r for r in recipients if r.fcm_token]
        if tokens_with_fcm:
            fcm_result = send_sos_push_to_multiple(
                fcm_tokens=[r.fcm_token for r in tokens_with_fcm],
                alert_type=payload.alert_type.value,
                victim_name=victim.full_name,
                distances_m=[r.distance_meters for r in tokens_with_fcm],
                latitude=payload.latitude,
                longitude=payload.longitude,
                alert_id=alert_id,
            )
            logger.info(
                f"FCM push: {fcm_result['sent']} sent, {fcm_result['failed']} failed "
                f"out of {len(tokens_with_fcm)} recipients"
            )

        return AlertTriggerResponse(
            status="success",
            alert_id=alert_id,
            alert_type=payload.alert_type,
            alert_status=AlertStatusEnum.ACTIVE,
            triggered_at=datetime.utcnow(),
            latitude=payload.latitude,
            longitude=payload.longitude,
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
        update_query = text("""
            UPDATE alerts 
            SET status = 'CANCELLED_BY_PIN', resolved_at = NOW() 
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
