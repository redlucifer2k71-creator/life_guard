import os
import logging
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse
from sqlalchemy import create_engine, text
from sqlalchemy.orm import declarative_base, sessionmaker

logger = logging.getLogger("lifeguard.db")

raw_url = os.getenv(
    "DATABASE_URL",
    "mysql+pymysql://root:password@localhost:3306/lifeguard_db"
)

# Auto-convert standard mysql:// prefix to mysql+pymysql://
if raw_url.startswith("mysql://"):
    raw_url = "mysql+pymysql://" + raw_url[len("mysql://"):]

# Sanitize query parameters for PyMySQL (PyMySQL expects SSL in connect_args, not in query string)
connect_args = {}
if "?" in raw_url:
    parsed = urlparse(raw_url)
    qs = parse_qs(parsed.query)
    
    ssl_keys = ("ssl-mode", "ssl_mode", "ssl", "ssl_verify_cert", "ssl_verify_identity", "ssl_ca")
    if any(k in qs for k in ssl_keys):
        connect_args["ssl"] = {}
        clean_qs = {k: v for k, v in qs.items() if k not in ssl_keys}
        clean_query = urlencode(clean_qs, doseq=True)
        raw_url = urlunparse(parsed._replace(query=clean_query))

# Auto-enable SSL for cloud databases (TiDB, Aiven, AWS, etc.)
if "localhost" not in raw_url and "127.0.0.1" not in raw_url and not raw_url.startswith("sqlite"):
    connect_args["ssl"] = {}

DATABASE_URL = raw_url

Base = declarative_base()


def init_extra_tables(target_engine):
    """Create spatial and auxiliary tables for either MySQL or SQLite."""
    try:
        with target_engine.begin() as conn:
            if target_engine.dialect.name == "sqlite":
                conn.execute(text("""
                    CREATE TABLE IF NOT EXISTS user_locations (
                        user_id INTEGER PRIMARY KEY,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        heading REAL,
                        speed REAL,
                        updated_at TEXT DEFAULT (datetime('now')),
                        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                """))
                conn.execute(text("""
                    CREATE TABLE IF NOT EXISTS alerts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        alert_type TEXT NOT NULL,
                        status TEXT DEFAULT 'ACTIVE',
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        triggered_at TEXT DEFAULT (datetime('now')),
                        resolved_at TEXT,
                        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                """))
                conn.execute(text("""
                    CREATE TABLE IF NOT EXISTS alert_recipients (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        alert_id INTEGER NOT NULL,
                        recipient_user_id INTEGER NOT NULL,
                        distance_meters REAL NOT NULL,
                        notified_at TEXT DEFAULT (datetime('now')),
                        fcm_message_id TEXT,
                        delivery_status TEXT DEFAULT 'SENT',
                        FOREIGN KEY (alert_id) REFERENCES alerts(id) ON DELETE CASCADE,
                        FOREIGN KEY (recipient_user_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                """))
            else:
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
        logger.info(f"Database tables verified for dialect: {target_engine.dialect.name}")
    except Exception as e:
        logger.warning(f"Notice during init_extra_tables ({target_engine.dialect.name}): {e}")


def _build_engine():
    # Attempt to connect to primary cloud database (TiDB / MySQL)
    if not DATABASE_URL.startswith("sqlite"):
        try:
            eng = create_engine(
                DATABASE_URL,
                connect_args=connect_args,
                pool_pre_ping=True,
                pool_recycle=3600,
                echo=False
            )
            # Quick connectivity verification
            with eng.connect() as conn:
                conn.execute(text("SELECT 1"))
            logger.info("Connected successfully to primary database.")
            return eng, False
        except Exception as e:
            logger.warning(
                f"Primary database connection failed ({e}). "
                "Engaging resilient embedded SQLite database for zero-downtime operation."
            )

    # Fallback to local SQLite engine
    sqlite_engine = create_engine(
        "sqlite:///./lifeguard_resilient.db",
        connect_args={"check_same_thread": False},
        echo=False
    )
    try:
        from app.models.user import User  # Ensure User model is registered
        Base.metadata.create_all(bind=sqlite_engine)
        init_extra_tables(sqlite_engine)
    except Exception as e:
        logger.warning(f"Error initializing SQLite fallback tables: {e}")

    return sqlite_engine, True


engine, is_fallback = _build_engine()
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def get_db():
    global engine, SessionLocal, is_fallback
    db = None
    try:
        db = SessionLocal()
        # Verify connection health
        db.execute(text("SELECT 1"))
        yield db
    except Exception as e:
        # If MySQL dropped during request, dynamically switch to SQLite
        if not is_fallback:
            logger.warning(f"Database connection interrupted ({e}). Auto-failing over to SQLite.")
            engine = create_engine(
                "sqlite:///./lifeguard_resilient.db",
                connect_args={"check_same_thread": False},
                echo=False
            )
            is_fallback = True
            Base.metadata.create_all(bind=engine)
            init_extra_tables(engine)
            SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
            if db:
                try:
                    db.close()
                except Exception:
                    pass
            db = SessionLocal()
            yield db
        else:
            raise e
    finally:
        if db:
            db.close()

