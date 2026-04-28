from sqlalchemy import Column, DateTime, Float, ForeignKey, Index, Integer, String

from app.database import Base


class TripPoint(Base):
    __tablename__ = "trip_points"
    __table_args__ = (
        Index(
            "uq_trip_points_trip_client_point_id",
            "trip_id",
            "client_point_id",
            unique=True,
        ),
    )

    id = Column(Integer, primary_key=True, index=True)
    trip_id = Column(Integer, ForeignKey("trips.id"), nullable=False, index=True)
    client_point_id = Column(String, nullable=True)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)
    timestamp = Column(DateTime, nullable=False)
    accuracy = Column(Float, nullable=True)
    speed = Column(Float, nullable=True)
