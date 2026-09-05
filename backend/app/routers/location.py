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


@router.post("/update", response_model=LocationUpdateResponse)
def update_location(payload: LocationUpdateRequest, db: Session = Depends(get_db)):
    """Save or update user's live GPS position using MySQL spatial POINT."""
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

    try:
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
    """Execute MySQL ST_Distance_Sphere spatial query to find active users within radius (100m - 1000m)."""
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

    try:
        results = db.execute(
            spatial_query,
            {
                "user_id": payload.user_id,
                "wkt_point": wkt_point,
                "radius_meters": payload.radius_meters,
            },
        ).mappings().all()
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Geospatial query error: {str(e)}",
        )

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

    return NearbySearchResponse(
        status="success",
        total_found=len(nearby_users),
        radius_meters=payload.radius_meters,
        nearby_users=nearby_users,
    )
