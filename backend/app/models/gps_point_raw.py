from datetime import datetime

from sqlalchemy import Boolean, Column, DateTime, Float, ForeignKey, Integer, Text

from app.database import Base


class GpsPointRaw(Base):
    __tablename__ = "gps_points_raw"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(Integer, ForeignKey("gps_devices.id"), nullable=False, index=True)
    company_id = Column(Integer, ForeignKey("companies.id"), nullable=False, index=True)
    vehicle_id = Column(Integer, ForeignKey("vehicles.id"), nullable=True, index=True)
    timestamp = Column(DateTime, nullable=False, index=True)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)
    speed_kmh = Column(Float, nullable=True)
    heading = Column(Float, nullable=True)
    accuracy_m = Column(Float, nullable=True)
    altitude_m = Column(Float, nullable=True)
    ignition = Column(Boolean, nullable=True)
    battery = Column(Float, nullable=True)
    raw_json = Column(Text, nullable=False)
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow)
