from sqlalchemy import Column, DateTime, Float, ForeignKey, Integer

from app.database import Base


class TripPoint(Base):
    __tablename__ = "trip_points"

    id = Column(Integer, primary_key=True, index=True)
    trip_id = Column(Integer, ForeignKey("trips.id"), nullable=False, index=True)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)
    timestamp = Column(DateTime, nullable=False)
    accuracy = Column(Float, nullable=True)
    speed = Column(Float, nullable=True)
