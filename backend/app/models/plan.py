from datetime import datetime

from sqlalchemy import Boolean, Column, DateTime, Integer, String, Text

from app.database import Base


class Plan(Base):
    __tablename__ = "plans"

    id = Column(Integer, primary_key=True, index=True)
    code = Column(String, nullable=False, unique=True, index=True)
    name = Column(String, nullable=False)
    max_users = Column(Integer, nullable=True)
    max_vehicles = Column(Integer, nullable=True)
    max_devices = Column(Integer, nullable=True)
    history_days = Column(Integer, nullable=True)
    features_json = Column(Text, nullable=True)
    is_active = Column(Boolean, nullable=False, default=True, server_default="1")
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow)
