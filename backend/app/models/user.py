from sqlalchemy import Column, BigInteger, String, Boolean, DateTime, func
from app.core.database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(BigInteger, primary_key=True, index=True, autoincrement=True)
    phone_number = Column(String(20), unique=True, nullable=False, index=True)
    full_name = Column(String(100), nullable=False)
    pin_hash = Column(String(255), nullable=False)
    fcm_token = Column(String(500), nullable=True)
    emergency_contact_phone = Column(String(20), nullable=True)
    is_active = Column(Boolean, default=True, nullable=False)
    created_at = Column(DateTime, server_default=func.now(), nullable=False)
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now(), nullable=False)
