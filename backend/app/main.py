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
        from app.core.database import init_extra_tables
        Base.metadata.create_all(bind=engine)
        init_extra_tables(engine)
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
