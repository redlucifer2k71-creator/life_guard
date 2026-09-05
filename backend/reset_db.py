from sqlalchemy import text
from app.core.database import Base, engine
from app.models import User  # Registers User model with Base.metadata

print("Resetting database schema...")

with engine.begin() as conn:
    conn.execute(text("SET FOREIGN_KEY_CHECKS = 0;"))
    conn.execute(text("DROP TABLE IF EXISTS alert_recipients;"))
    conn.execute(text("DROP TABLE IF EXISTS alert_dispatches;"))
    conn.execute(text("DROP TABLE IF EXISTS alerts;"))
    conn.execute(text("DROP TABLE IF EXISTS user_locations;"))
    conn.execute(text("DROP TABLE IF EXISTS users;"))
    conn.execute(text("SET FOREIGN_KEY_CHECKS = 1;"))

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

print("SUCCESS: Database reset successfully!")