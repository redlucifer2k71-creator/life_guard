from typing import Optional, List
from pydantic import BaseModel, Field


class LocationUpdateRequest(BaseModel):
    user_id: int = Field(..., description="ID of the user updating location")
    latitude: float = Field(..., ge=-90.0, le=90.0, description="Latitude in degrees (-90 to 90)")
    longitude: float = Field(..., ge=-180.0, le=180.0, description="Longitude in degrees (-180 to 180)")
    speed: Optional[float] = Field(None, ge=0.0, description="Speed in meters per second")
    heading: Optional[float] = Field(None, ge=0.0, le=360.0, description="Heading in degrees (0 to 360)")


class LocationUpdateResponse(BaseModel):
    status: str
    user_id: int
    updated_at: str


class NearbySearchRequest(BaseModel):
    user_id: int = Field(..., description="ID of requesting user (to exclude from search)")
    latitude: float = Field(..., ge=-90.0, le=90.0)
    longitude: float = Field(..., ge=-180.0, le=180.0)
    radius_meters: float = Field(500.0, ge=100.0, le=1000.0, description="Radius between 100m and 1000m")


class NearbyUserResponse(BaseModel):
    user_id: int
    full_name: str
    phone_number: str
    fcm_token: Optional[str] = None
    latitude: float
    longitude: float
    distance_meters: float


class NearbySearchResponse(BaseModel):
    status: str
    total_found: int
    radius_meters: float
    nearby_users: List[NearbyUserResponse]
