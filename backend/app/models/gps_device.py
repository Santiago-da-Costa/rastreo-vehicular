from datetime import datetime

from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Integer, String

from app.database import Base


class GpsDevice(Base):
    __tablename__ = "gps_devices"

    id = Column(Integer, primary_key=True, index=True)
    company_id = Column(Integer, ForeignKey("companies.id"), nullable=False, index=True)
    vehicle_id = Column(Integer, ForeignKey("vehicles.id"), nullable=True, index=True)
    name = Column(String, nullable=True)
    device_uid = Column(String, nullable=False, unique=True, index=True)
    device_token_hash = Column(String, nullable=False)
    provider = Column(String, nullable=True)
    protocol = Column(String, nullable=True)
    is_active = Column(Boolean, nullable=False, default=True, server_default="1")
    last_seen_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow)
