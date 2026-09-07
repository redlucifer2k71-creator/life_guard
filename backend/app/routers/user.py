import hashlib
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError

from app.core.database import get_db
from app.models.user import User
from app.schemas.user import (
    UserRegisterRequest, UserRegisterResponse,
    UserLoginRequest, UserLoginResponse,
    UserResponse
)

router = APIRouter(prefix="/api/v1/users", tags=["Users & Authentication"])


def hash_pin(pin: str) -> str:
    """Hash a numeric PIN securely using SHA-256 with salt."""
    salt = "lifeguard_pin_salt_2026"
    return hashlib.sha256((pin + salt).encode("utf-8")).hexdigest()


import re
from sqlalchemy import or_

def extract_last_10_digits(phone: str) -> str:
    """Extract digits and return the last 10 digits for country-code-agnostic matching."""
    digits = re.sub(r"\D", "", phone)
    return digits[-10:] if len(digits) >= 10 else digits


def find_user_by_phone(db: Session, raw_phone: str) -> User | None:
    """Finds user by exact phone, or by matching the last 10 digits (ignoring +91, 0, spaces)."""
    user = db.query(User).filter(User.phone_number == raw_phone).first()
    if user:
        return user
    last10 = extract_last_10_digits(raw_phone)
    if len(last10) == 10:
        return db.query(User).filter(User.phone_number.like(f"%{last10}")).first()
    return None


@router.post("/register", response_model=UserRegisterResponse, status_code=status.HTTP_201_CREATED)
def register_user(payload: UserRegisterRequest, db: Session = Depends(get_db)):
    """Register a new user or update existing user's 6-digit PIN and credentials."""
    try:
        existing_user = find_user_by_phone(db, payload.phone_number)
        pin_hash = hash_pin(payload.pin)

        if existing_user:
            # User already exists: update credentials & PIN seamlessly
            existing_user.full_name = payload.full_name
            existing_user.pin_hash = pin_hash
            if payload.emergency_contact_phone:
                existing_user.emergency_contact_phone = payload.emergency_contact_phone
            if payload.fcm_token:
                existing_user.fcm_token = payload.fcm_token
            existing_user.is_active = True
            db.commit()
            db.refresh(existing_user)
            return UserRegisterResponse(
                status="success",
                message="Account updated and PIN reset successfully",
                user=UserResponse.model_validate(existing_user),
            )

        new_user = User(
            full_name=payload.full_name,
            phone_number=payload.phone_number,
            pin_hash=pin_hash,
            emergency_contact_phone=payload.emergency_contact_phone,
            fcm_token=payload.fcm_token,
            is_active=True,
        )

        db.add(new_user)
        db.commit()
        db.refresh(new_user)

        return UserRegisterResponse(
            status="success",
            message="User registered successfully",
            user=UserResponse.model_validate(new_user),
        )
    except HTTPException:
        raise
    except Exception as e:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Registration Error: {str(e)}",
        )


@router.post("/login", response_model=UserLoginResponse)
def login_user(payload: UserLoginRequest, db: Session = Depends(get_db)):
    """Authenticate user with phone number (fuzzy 10-digit match) and PIN."""
    try:
        user = find_user_by_phone(db, payload.phone_number)
        if not user:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Phone number not registered. Please register your account.",
            )

        provided_hash = hash_pin(payload.pin)
        if provided_hash != user.pin_hash:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid secret PIN. If you forgot your PIN, simply re-register to reset it.",
            )

        return UserLoginResponse(
            status="success",
            message="Login successful",
            user=UserResponse.model_validate(user),
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Login Error: {str(e)}",
        )


from pydantic import BaseModel

class FcmTokenUpdateRequest(BaseModel):
    user_id: int
    fcm_token: str

class FcmTokenUpdateResponse(BaseModel):
    status: str
    message: str


@router.post("/fcm-token", response_model=FcmTokenUpdateResponse)
def update_fcm_token(payload: FcmTokenUpdateRequest, db: Session = Depends(get_db)):
    """Register or update a device's FCM push notification token for a user."""
    user = db.query(User).filter(User.id == payload.user_id, User.is_active == True).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Active user with ID {payload.user_id} not found.",
        )

    # Maintain comma-separated list of active device tokens for this user
    incoming_token = payload.fcm_token.strip()
    existing_tokens = [t.strip() for t in (user.fcm_token or "").split(",") if t.strip()]
    
    if incoming_token in existing_tokens:
        # Move to end (most recently active)
        existing_tokens.remove(incoming_token)
    existing_tokens.append(incoming_token)
    
    # Retain the most recent 5 device tokens
    user.fcm_token = ",".join(existing_tokens[-5:])
    db.commit()

    return FcmTokenUpdateResponse(
        status="success",
        message=f"FCM token updated for user {payload.user_id} (active devices: {len(existing_tokens[-5:])})",
    )
