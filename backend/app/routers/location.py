import math
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy import text

from app.core.database import get_db
from app.schemas.location import (
    LocationUpdateRequest,
    LocationUpdateResponse,
    NearbySearchRequest,
    NearbySearchResponse,
    NearbyUserResponse,
)

router = APIRouter(prefix="/api/v1/location", tags=["Location & Proximity"])


def haversine_distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate great-circle distance between two GPS points in meters."""
    R = 6371000.0  # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2.0)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0)**2
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c


@router.post("/update", response_model=LocationUpdateResponse)
def update_location(payload: LocationUpdateRequest, db: Session = Depends(get_db)):
    """Save or update user's live GPS position (supports MySQL spatial POINT and SQLite)."""
    is_sqlite = db.bind.dialect.name == "sqlite"

    try:
        if is_sqlite:
            db.execute(
                text("""
                    INSERT INTO user_locations (user_id, latitude, longitude, heading, speed, updated_at)
                    VALUES (:user_id, :latitude, :longitude, :heading, :speed, datetime('now'))
                    ON CONFLICT(user_id) DO UPDATE SET
                        latitude = excluded.latitude,
                        longitude = excluded.longitude,
                        heading = excluded.heading,
                        speed = excluded.speed,
                        updated_at = datetime('now');
                """),
                {
                    "user_id": payload.user_id,
                    "latitude": payload.latitude,
                    "longitude": payload.longitude,
                    "heading": payload.heading,
                    "speed": payload.speed,
                },
            )
        else:
            wkt_point = f"POINT({payload.longitude} {payload.latitude})"
            upsert_query = text("""
                INSERT INTO user_locations (user_id, latitude, longitude, location_point, heading, speed, updated_at)
                VALUES (
                    :user_id, 
                    :latitude, 
                    :longitude, 
                    ST_SRID(ST_PointFromText(:wkt_point), 4326), 
                    :heading, 
                    :speed, 
                    NOW()
                )
                ON DUPLICATE KEY UPDATE
                    latitude = VALUES(latitude),
                    longitude = VALUES(longitude),
                    location_point = VALUES(location_point),
                    heading = VALUES(heading),
                    speed = VALUES(speed),
                    updated_at = NOW();
            """)
            db.execute(
                upsert_query,
                {
                    "user_id": payload.user_id,
                    "latitude": payload.latitude,
                    "longitude": payload.longitude,
                    "wkt_point": wkt_point,
                    "heading": payload.heading,
                    "speed": payload.speed,
                },
            )
        db.commit()
    except Exception as e:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to update user location: {str(e)}",
        )

    return LocationUpdateResponse(
        status="success",
        user_id=payload.user_id,
        updated_at=datetime.utcnow().isoformat(),
    )


@router.post("/nearby", response_model=NearbySearchResponse)
def find_nearby_users(payload: NearbySearchRequest, db: Session = Depends(get_db)):
    """Find active users within radius (supports MySQL ST_Distance_Sphere and SQLite Haversine)."""
    is_sqlite = db.bind.dialect.name == "sqlite"

    try:
        nearby_users = []
        if is_sqlite:
            rows = db.execute(
                text("""
                    SELECT 
                        u.id AS user_id,
                        u.full_name,
                        u.phone_number,
                        u.fcm_token,
                        ul.latitude,
                        ul.longitude
                    FROM user_locations ul
                    JOIN users u ON ul.user_id = u.id
                    WHERE u.id != :user_id
                      AND u.is_active = 1;
                """),
                {"user_id": payload.user_id},
            ).mappings().all()

            for row in rows:
                dist = haversine_distance_meters(
                    payload.latitude, payload.longitude,
                    float(row["latitude"]), float(row["longitude"])
                )
                if dist <= payload.radius_meters:
                    nearby_users.append(
                        NearbyUserResponse(
                            user_id=row["user_id"],
                            full_name=row["full_name"],
                            phone_number=row["phone_number"],
                            fcm_token=row["fcm_token"],
                            latitude=float(row["latitude"]),
                            longitude=float(row["longitude"]),
                            distance_meters=round(dist, 2),
                        )
                    )
            nearby_users.sort(key=lambda x: x.distance_meters)
        else:
            wkt_point = f"POINT({payload.longitude} {payload.latitude})"
            spatial_query = text("""
                SELECT 
                    u.id AS user_id,
                    u.full_name,
                    u.phone_number,
                    u.fcm_token,
                    ul.latitude,
                    ul.longitude,
                    ST_Distance_Sphere(ul.location_point, ST_SRID(ST_PointFromText(:wkt_point), 4326)) AS distance_meters
                FROM user_locations ul
                JOIN users u ON ul.user_id = u.id
                WHERE u.id != :user_id
                  AND u.is_active = 1
                  AND ST_Distance_Sphere(ul.location_point, ST_SRID(ST_PointFromText(:wkt_point), 4326)) <= :radius_meters
                ORDER BY distance_meters ASC;
            """)
            results = db.execute(
                spatial_query,
                {
                    "user_id": payload.user_id,
                    "wkt_point": wkt_point,
                    "radius_meters": payload.radius_meters,
                },
            ).mappings().all()

            nearby_users = [
                NearbyUserResponse(
                    user_id=row["user_id"],
                    full_name=row["full_name"],
                    phone_number=row["phone_number"],
                    fcm_token=row["fcm_token"],
                    latitude=float(row["latitude"]),
                    longitude=float(row["longitude"]),
                    distance_meters=round(float(row["distance_meters"]), 2),
                )
                for row in results
            ]
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Geospatial query error: {str(e)}",
        )

    return NearbySearchResponse(
        status="success",
        total_found=len(nearby_users),
        radius_meters=payload.radius_meters,
        nearby_users=nearby_users,
    )
