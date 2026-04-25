from sqlalchemy import Column, ForeignKey, Integer, String

from app.database import Base


class Vehicle(Base):
    __tablename__ = "vehicles"

    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String, nullable=False)
    matricula = Column(String, nullable=False)
    descripcion = Column(String, nullable=True)
    company_id = Column(Integer, ForeignKey("companies.id"), nullable=True, index=True)
