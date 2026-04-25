from sqlalchemy.orm import Session

from app.config import (
    CREATE_INITIAL_ADMIN,
    INITIAL_ADMIN_EMAIL,
    INITIAL_ADMIN_FULL_NAME,
    INITIAL_ADMIN_PASSWORD,
    INITIAL_ADMIN_USERNAME,
)
from app.models.company import Company
from app.models.user import User
from app.utils.security import hash_password


def ensure_initial_admin(db: Session) -> None:
    if not CREATE_INITIAL_ADMIN:
        return

    if not INITIAL_ADMIN_USERNAME or not INITIAL_ADMIN_PASSWORD:
        raise RuntimeError(
            "CREATE_INITIAL_ADMIN is enabled, but INITIAL_ADMIN_USERNAME or "
            "INITIAL_ADMIN_PASSWORD is missing"
        )

    existing_user = db.query(User).filter(User.username == INITIAL_ADMIN_USERNAME).first()
    if existing_user is not None:
        return

    default_company = db.query(Company).filter(Company.slug == "default").first()

    admin = User(
        username=INITIAL_ADMIN_USERNAME,
        password_hash=hash_password(INITIAL_ADMIN_PASSWORD),
        full_name=INITIAL_ADMIN_FULL_NAME,
        email=INITIAL_ADMIN_EMAIL,
        company_id=default_company.id if default_company is not None else None,
        role="admin",
        is_active=True,
    )
    db.add(admin)
    db.commit()
