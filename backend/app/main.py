from fastapi import FastAPI
from app.core.database import Base, engine
from app.models import User  # Ensures models are registered

# Create database tables if they do not exist
from app.routers import alert, location, user

Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="Life Guard Emergency SOS API",
    description="Backend API for real-time emergency broadcasts and geospatial tracking.",
    version="1.0.0"
)

app.include_router(user.router)
app.include_router(location.router)
app.include_router(alert.router)


@app.get("/")
def read_root():
    return {"status": "Life Guard API is running"}
