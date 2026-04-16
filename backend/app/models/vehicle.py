from sqlalchemy import Column, Integer, String

from app.database import Base


class Vehicle(Base):
    __tablename__ = "vehicles"

    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String, nullable=False)
    matricula = Column(String, nullable=False)
    descripcion = Column(String, nullable=True)
