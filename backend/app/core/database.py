import os
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse
from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker

raw_url = os.getenv(
    "DATABASE_URL",
    "mysql+pymysql://root:password@localhost:3306/lifeguard_db"
)

# Auto-convert standard mysql:// prefix to mysql+pymysql://
if raw_url.startswith("mysql://"):
    raw_url = "mysql+pymysql://" + raw_url[len("mysql://"):]

# Sanitize query parameters (PyMySQL does not accept ?ssl-mode=REQUIRED)
connect_args = {}
if "?" in raw_url:
    parsed = urlparse(raw_url)
    qs = parse_qs(parsed.query)
    
    # If cloud SSL was requested in the URL
    if any(k in qs for k in ("ssl-mode", "ssl_mode", "ssl")):
        connect_args["ssl"] = {}
        # Remove unsupported query parameters
        clean_qs = {k: v for k, v in qs.items() if k not in ("ssl-mode", "ssl_mode", "ssl")}
        clean_query = urlencode(clean_qs, doseq=True)
        raw_url = urlunparse(parsed._replace(query=clean_query))

# Auto-enable SSL for cloud databases (Aiven, AWS, etc.)
if "localhost" not in raw_url and "127.0.0.1" not in raw_url:
    connect_args["ssl"] = {}

DATABASE_URL = raw_url

engine = create_engine(
    DATABASE_URL,
    connect_args=connect_args,
    pool_pre_ping=True,
    pool_recycle=3600,
    echo=False
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
