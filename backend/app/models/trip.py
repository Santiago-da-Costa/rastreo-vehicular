from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Integer, String

from app.database import Base


class Trip(Base):
    __tablename__ = "trips"

    id = Column(Integer, primary_key=True, index=True)
    vehicle_id = Column(Integer, ForeignKey("vehicles.id"), nullable=False)
    company_id = Column(Integer, ForeignKey("companies.id"), nullable=True, index=True)
    categoria = Column(String, nullable=False)
    start_time = Column(DateTime, nullable=False)
    end_time = Column(DateTime, nullable=True)
    status = Column(String, nullable=False)
    is_manual = Column(Boolean, nullable=False, default=False, server_default="0")
