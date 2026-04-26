import re
import unicodedata

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.company import Company
from app.models.plan import Plan
from app.models.user import User
from app.schemas.company import CompanyRegistrationRequest, CompanyRegistrationResponse
from app.utils.security import hash_password

router = APIRouter(prefix="/companies", tags=["companies"])

DEFAULT_PLAN_CODE = "default"


def _build_company_slug(company_name: str) -> str:
    normalized = unicodedata.normalize("NFKD", company_name)
    ascii_name = normalized.encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"\s+", "-", ascii_name.strip().lower())
    slug = re.sub(r"[^a-z0-9-]", "-", slug)
    slug = re.sub(r"-{2,}", "-", slug).strip("-")
    if not slug:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Company name must generate a valid slug",
        )
    return slug


def _raise_username_conflict() -> None:
    raise HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail="Username already exists",
    )


def _raise_slug_conflict() -> None:
    raise HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail="Company slug already exists",
    )


@router.post("/register", response_model=CompanyRegistrationResponse, status_code=status.HTTP_201_CREATED)
def register_company(
    payload: CompanyRegistrationRequest,
    db: Session = Depends(get_db),
):
    if db.query(User.id).filter(User.username == payload.owner_username).first() is not None:
        _raise_username_conflict()

    slug = _build_company_slug(payload.company_name)
    if db.query(Company.id).filter(Company.slug == slug).first() is not None:
        _raise_slug_conflict()

    plan = db.query(Plan).filter(Plan.code == DEFAULT_PLAN_CODE).first()
    if plan is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Default plan is not configured",
        )

    try:
        company = Company(
            name=payload.company_name,
            slug=slug,
            status="active",
            plan_id=plan.id,
        )
        owner = User(
            username=payload.owner_username,
            password_hash=hash_password(payload.owner_password),
            full_name=payload.owner_full_name,
            email=payload.owner_email,
            role="admin",
            is_active=True,
        )

        db.add(company)
        db.flush()

        owner.company_id = company.id
        db.add(owner)
        db.flush()

        company.owner_user_id = owner.id
        db.flush()
        db.commit()

        return CompanyRegistrationResponse(
            company_id=company.id,
            company_name=company.name,
            owner_username=owner.username,
        )
    except HTTPException:
        db.rollback()
        raise
    except IntegrityError as exc:
        db.rollback()
        error_text = str(exc.orig).lower() if exc.orig is not None else str(exc).lower()
        if "users.username" in error_text or "username" in error_text:
            _raise_username_conflict()
        if "companies.slug" in error_text or "slug" in error_text:
            _raise_slug_conflict()
        raise
    except Exception:
        db.rollback()
        raise
