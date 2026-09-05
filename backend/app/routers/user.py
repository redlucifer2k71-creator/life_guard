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


@router.post("/register", response_model=UserRegisterResponse, status_code=status.HTTP_201_CREATED)
def register_user(payload: UserRegisterRequest, db: Session = Depends(get_db)):
    """Register a new user with hashed PIN and initial credentials."""
    try:
        existing_user = db.query(User).filter(User.phone_number == payload.phone_number).first()
        if existing_user:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="A user with this phone number is already registered.",
            )

        pin_hash = hash_pin(payload.pin)

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
    """Authenticate user with phone number and PIN."""
    try:
        user = db.query(User).filter(User.phone_number == payload.phone_number).first()
        if not user:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid phone number or PIN.",
            )

        provided_hash = hash_pin(payload.pin)
        if provided_hash != user.pin_hash:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid phone number or PIN.",
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

    user.fcm_token = payload.fcm_token
    db.commit()

    return FcmTokenUpdateResponse(
        status="success",
        message=f"FCM token updated for user {payload.user_id}",
    )
