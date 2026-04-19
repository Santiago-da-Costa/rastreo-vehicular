from datetime import datetime

from pydantic import BaseModel, field_validator

from app.utils.permissions import normalize_role


class UserBase(BaseModel):
    username: str
    full_name: str
    email: str | None = None
    role: str
    is_active: bool = True

    @field_validator("username", "full_name")
    @classmethod
    def validate_required_text(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Field cannot be empty")
        return value

    @field_validator("role")
    @classmethod
    def validate_role(cls, role: str) -> str:
        return normalize_role(role)


class UserCreate(UserBase):
    password: str

    @field_validator("password")
    @classmethod
    def validate_password(cls, password: str) -> str:
        if not password:
            raise ValueError("Password cannot be empty")
        return password


class UserUpdate(BaseModel):
    username: str | None = None
    password: str | None = None
    full_name: str | None = None
    email: str | None = None
    role: str | None = None
    is_active: bool | None = None

    @field_validator("username", "full_name")
    @classmethod
    def validate_optional_text(cls, value: str | None) -> str | None:
        if value is None:
            raise ValueError("Field cannot be null")
        value = value.strip()
        if not value:
            raise ValueError("Field cannot be empty")
        return value

    @field_validator("password")
    @classmethod
    def validate_optional_password(cls, password: str | None) -> str | None:
        if password is None:
            raise ValueError("Password cannot be null")
        if not password:
            raise ValueError("Password cannot be empty")
        return password

    @field_validator("role")
    @classmethod
    def validate_role(cls, role: str | None) -> str | None:
        if role is None:
            raise ValueError("Role cannot be null")
        return normalize_role(role)


class UserResponse(BaseModel):
    id: int
    username: str
    full_name: str
    email: str | None = None
    role: str
    is_active: bool
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class UserVehicleAssignment(BaseModel):
    vehicle_ids: list[int]


class UserMeResponse(UserResponse):
    permissions: dict[str, bool]
    vehicle_ids: list[int]
