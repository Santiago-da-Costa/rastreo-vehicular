from pydantic import BaseModel

from app.schemas.users import UserMeResponse, UserResponse


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"

