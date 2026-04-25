from datetime import datetime

from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Integer, String, UniqueConstraint

from app.database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, nullable=False, unique=True, index=True)
    password_hash = Column(String, nullable=False)
    full_name = Column(String, nullable=False)
    email = Column(String, nullable=True)
    company_id = Column(Integer, ForeignKey("companies.id"), nullable=True, index=True)
    role = Column(String, nullable=False, default="user", server_default="user")
    is_active = Column(Boolean, nullable=False, default=True, server_default="1")
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow)


class UserVehicleAccess(Base):
    __tablename__ = "user_vehicle_access"
    __table_args__ = (
        UniqueConstraint("user_id", "vehicle_id", name="uq_user_vehicle_access_user_vehicle"),
    )

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    vehicle_id = Column(Integer, ForeignKey("vehicles.id"), nullable=False, index=True)
