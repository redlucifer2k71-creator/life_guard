from typing import Optional, List
from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field, field_validator


class AlertTypeEnum(str, Enum):
    ROUTE_DEVIATION = "ROUTE_DEVIATION"
    GUARD_MODE_DOUBLE_PRESS = "GUARD_MODE_DOUBLE_PRESS"
    GUARD_MODE_RELEASE = "GUARD_MODE_RELEASE"
    TIMER_CHECKIN_EXPIRED = "TIMER_CHECKIN_EXPIRED"


class AlertStatusEnum(str, Enum):
    ACTIVE = "ACTIVE"
    RESOLVED = "RESOLVED"
    CANCELLED_BY_PIN = "CANCELLED_BY_PIN"


class AlertTriggerRequest(BaseModel):
    user_id: int = Field(..., description="ID of victim user triggering the SOS alert")
    alert_type: AlertTypeEnum = Field(..., description="Type of SOS alert triggered")
    latitude: float = Field(..., ge=-90.0, le=90.0, description="Latitude of emergency")
    longitude: float = Field(..., ge=-180.0, le=180.0, description="Longitude of emergency")
    radius_meters: float = Field(1000.0, ge=50.0, le=5000.0, description="Radius for community broadcast (50m - 5000m)")
    sender_fcm_token: Optional[str] = Field(None, description="FCM token of the sending device to avoid redundant self-alert")


class NotifiedRecipient(BaseModel):
    user_id: int
    full_name: str
    phone_number: str
    fcm_token: Optional[str] = None
    distance_meters: float


class AlertTriggerResponse(BaseModel):
    status: str
    alert_id: int
    alert_type: AlertTypeEnum
    alert_status: AlertStatusEnum
    triggered_at: datetime
    latitude: float
    longitude: float
    total_recipients_notified: int
    recipients: List[NotifiedRecipient]


class AlertResolveRequest(BaseModel):
    alert_id: int = Field(..., description="ID of alert to resolve")
    user_id: int = Field(..., description="ID of user resolving alert")
    pin: str = Field(..., min_length=4, max_length=6, description="Secret PIN to cancel alert")

    @field_validator("pin")
    def validate_pin_numeric(cls, v: str) -> str:
        if not v.isdigit():
            raise ValueError("PIN must contain only numeric digits")
        return v


class AlertResolveResponse(BaseModel):
    status: str
    message: str
    alert_id: int
    resolved_at: datetime
