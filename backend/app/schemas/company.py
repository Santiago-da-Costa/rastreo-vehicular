from pydantic import BaseModel, field_validator


class CompanyRegistrationRequest(BaseModel):
    company_name: str
    owner_username: str
    owner_password: str
    owner_full_name: str
    owner_email: str | None = None

    @field_validator("company_name", "owner_username", "owner_full_name")
    @classmethod
    def validate_required_text(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Field cannot be empty")
        return value

    @field_validator("owner_password")
    @classmethod
    def validate_password(cls, password: str) -> str:
        if not password:
            raise ValueError("Password cannot be empty")
        return password

    @field_validator("owner_email")
    @classmethod
    def normalize_optional_email(cls, email: str | None) -> str | None:
        if email is None:
            return None
        email = email.strip()
        return email or None


class CompanyRegistrationResponse(BaseModel):
    company_id: int
    company_name: str
    owner_username: str
