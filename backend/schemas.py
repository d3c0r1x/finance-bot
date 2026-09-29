from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, Field, field_validator
from database.models import CATEGORIES

Money = Annotated[float, Field(ge=0, le=100_000_000, allow_inf_nan=False)]
PositiveMoney = Annotated[float, Field(gt=0, le=100_000_000, allow_inf_nan=False)]


class Credentials(BaseModel):
    username: str = Field(min_length=3, max_length=40, pattern=r"^[a-zA-Z0-9_.-]+$")
    password: str = Field(min_length=8, max_length=128)


class Registration(Credentials):
    display_name: str = Field(min_length=1, max_length=80)
    language: Literal["ru", "en"] = "ru"


class Refresh(BaseModel):
    refresh_token: str = Field(min_length=20, max_length=256)


class Transaction(BaseModel):
    amount: PositiveMoney
    category: str = "прочее"
    description: str = Field(default="", max_length=1000)
    tx_type: Literal["expense", "income", "debt_payment"] = "expense"
    debt_target: str | None = Field(default=None, max_length=80)
    created_at: datetime | None = None

    @field_validator("amount")
    @classmethod
    def cents(cls, value):
        if abs(value - round(value, 2)) > 1e-8:
            raise ValueError("amount_requires_at_most_two_decimal_places")
        return value

    @field_validator("category")
    @classmethod
    def known_category(cls, value):
        if value not in CATEGORIES:
            raise ValueError("unknown_category")
        return value

    @field_validator("created_at")
    @classmethod
    def naive_date(cls, value):
        if value is not None and value.tzinfo is not None:
            raise ValueError("use_local_datetime_without_offset")
        return value


class ReceiptItem(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    qty: PositiveMoney = 1
    price: Money = 0
    sum: Money = 0


class ReceiptConfirm(Transaction):
    items: list[ReceiptItem] = Field(default_factory=list, max_length=100)
    confirmation_id: str = Field(min_length=16, max_length=100)


class Budget(BaseModel):
    total: Money
    limits: dict[str, Money] = Field(default_factory=dict, max_length=10)
    weekly_food: Money = 0

    @field_validator("limits")
    @classmethod
    def known_categories(cls, value):
        if any(key not in CATEGORIES for key in value):
            raise ValueError("unknown_category")
        return value


class Debt(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    current_amount: Money
    interest_rate: Annotated[float, Field(ge=0, le=1000, allow_inf_nan=False)] = 0
    min_payment: Money = 0


class Payment(BaseModel):
    amount: PositiveMoney


class TextInput(BaseModel):
    text: str = Field(min_length=1, max_length=2000)


class WorkspaceSettings(BaseModel):
    mode: Literal["solo", "couple"] = "solo"
    default_visibility: Literal["private", "shared", "amount_only"] = "private"
    default_split: Literal["none", "equal", "percent", "manual"] = "none"
    partner_name: str = Field(default="", max_length=80)
    owner_share: Annotated[float, Field(ge=0, le=100, allow_inf_nan=False)] = 50


class Preferences(BaseModel):
    language: Literal["ru", "en"] = "ru"
    theme: Literal["system", "light", "dark"] = "system"
    display_name: str = Field(min_length=1, max_length=80)
    onboarded: bool = False


class Mark(BaseModel):
    key: str = Field(min_length=1, max_length=300)


class ImportConfirm(BaseModel):
    preview_id: str = Field(min_length=16, max_length=100)
