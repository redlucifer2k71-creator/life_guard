from contextlib import asynccontextmanager
import logging
from fastapi import FastAPI
from sqlalchemy import text
from app.core.database import Base, engine
from app.models import User  # Ensures models are registered
from app.routers import alert, location, user

logger = logging.getLogger("uvicorn")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Automatically verify and create all required tables on startup
    try:
        Base.metadata.create_all(bind=engine)
        with engine.begin() as conn:
            conn.execute(text("""
                CREATE TABLE IF NOT EXISTS user_locations (
                    user_id BIGINT PRIMARY KEY,
                    latitude DECIMAL(10, 8) NOT NULL,
                    longitude DECIMAL(11, 8) NOT NULL,
                    location_point POINT NOT NULL SRID 4326,
                    heading FLOAT NULL,
                    speed FLOAT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    SPATIAL INDEX idx_location_point (location_point)
                ) ENGINE=InnoDB;
            """))
            conn.execute(text("""
                CREATE TABLE IF NOT EXISTS alerts (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    alert_type ENUM('ROUTE_DEVIATION', 'GUARD_MODE_DOUBLE_PRESS', 'GUARD_MODE_RELEASE', 'TIMER_CHECKIN_EXPIRED') NOT NULL,
                    status ENUM('ACTIVE', 'RESOLVED', 'CANCELLED_BY_PIN') DEFAULT 'ACTIVE',
                    latitude DECIMAL(10, 8) NOT NULL,
                    longitude DECIMAL(11, 8) NOT NULL,
                    location_point POINT NOT NULL SRID 4326,
                    triggered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    resolved_at TIMESTAMP NULL,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    SPATIAL INDEX idx_alert_point (location_point)
                ) ENGINE=InnoDB;
            """))
            conn.execute(text("""
                CREATE TABLE IF NOT EXISTS alert_recipients (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    alert_id BIGINT NOT NULL,
                    recipient_user_id BIGINT NOT NULL,
                    distance_meters DOUBLE NOT NULL,
                    notified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    fcm_message_id VARCHAR(255) NULL,
                    delivery_status ENUM('SENT', 'DELIVERED', 'FAILED') DEFAULT 'SENT',
                    FOREIGN KEY (alert_id) REFERENCES alerts(id) ON DELETE CASCADE,
                    FOREIGN KEY (recipient_user_id) REFERENCES users(id) ON DELETE CASCADE
                ) ENGINE=InnoDB;
            """))
        logger.info("Database tables initialized successfully.")
    except Exception as e:
        logger.warning(f"Database initialization notice: {e}")
    yield

app = FastAPI(
    title="Life Guard Emergency SOS API",
    description="Backend API for real-time emergency broadcasts and geospatial tracking.",
    version="1.0.0",
    lifespan=lifespan
)

app.include_router(user.router)
app.include_router(location.router)
app.include_router(alert.router)


@app.get("/")
def read_root():
    return {"status": "Life Guard API is running"}
