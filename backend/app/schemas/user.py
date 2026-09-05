from typing import Optional
from datetime import datetime
from pydantic import BaseModel, Field, field_validator


class UserRegisterRequest(BaseModel):
    full_name: str = Field(..., min_length=2, max_length=100, description="Full name of the user")
    phone_number: str = Field(..., min_length=10, max_length=20, description="Phone number")
    pin: str = Field(..., min_length=4, max_length=6, description="4 to 6 digit secret PIN")
    emergency_contact_phone: Optional[str] = Field(None, description="Primary emergency contact phone")
    fcm_token: Optional[str] = Field(None, description="Firebase Cloud Messaging device token")

    @field_validator("pin")
    def validate_pin_numeric(cls, v: str) -> str:
        if not v.isdigit():
            raise ValueError("PIN must contain only numeric digits")
        return v


class UserResponse(BaseModel):
    id: int
    full_name: str
    phone_number: str
    emergency_contact_phone: Optional[str] = None
    fcm_token: Optional[str] = None
    is_active: bool
    created_at: datetime

    class Config:
        from_attributes = True


class UserRegisterResponse(BaseModel):
    status: str
    message: str
    user: UserResponse


class UserLoginRequest(BaseModel):
    phone_number: str = Field(..., min_length=10, max_length=20)
    pin: str = Field(..., min_length=4, max_length=6)

    @field_validator("pin")
    def validate_pin_numeric(cls, v: str) -> str:
        if not v.isdigit():
            raise ValueError("PIN must contain only numeric digits")
        return v


class UserLoginResponse(BaseModel):
    status: str
    message: str
    user: UserResponse

