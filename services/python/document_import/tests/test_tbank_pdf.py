from pathlib import Path
import json

from jsonschema import Draft202012Validator
import pytest

from services.python.document_import import tbank_pdf


def install_pdf_text(monkeypatch, tmp_path: Path, pages: list[str]) -> Path:
    pdf_path = tmp_path / "statement.pdf"
    pdf_path.write_bytes(b"%PDF-1.7 synthetic test fixture")

    class Page:
        def __init__(self, text: str):
            self.text = text

        def extract_text(self):
            return self.text

    class Reader:
        def __init__(self, _path):
            self.pages = [Page(text) for text in pages]

    monkeypatch.setattr(tbank_pdf, "PdfReader", Reader)
    return pdf_path


def test_parses_tbank_operations_and_validates_all_statement_totals(monkeypatch, tmp_path):
    first_page = """Справка о движении средств
18.09.2026
06:40
18.09.2026
06:40
-1 000.00 ₽
-1 000.00 ₽
Оплата в Пятёрочка Москва RUS
Терминал 12345
1234
"""
    second_page = """19.09.2026
09:15
19.09.2026
09:15
+500.00 ₽
+500.00 ₽
Зачисление заработной платы
—
1 000,00 ₽Расходы:
500,00 ₽Пополнения:
"""
    path = install_pdf_text(monkeypatch, tmp_path, [first_page, second_page])

    result = tbank_pdf.parse_tbank_pdf(path)

    assert result.quality == "valid"
    assert result.parse_version == "tbank-pdf.v1"
    assert (result.period_start, result.period_end) == ("2026-09-18", "2026-09-19")
    assert result.parsed_expense_total == "1000.00"
    assert result.expected_expense_total == "1000.00"
    assert result.parsed_income_total == "500.00"
    assert result.expected_income_total == "500.00"
    assert [operation.signed_amount for operation in result.operations] == ["-1000.00", "500.00"]
    assert result.operations[0].kind == "purchase"
    assert result.operations[0].merchant == "Пятёрочка"
    assert result.operations[0].card_last4 == "1234"
    assert result.operations[1].kind == "income"
    assert result.operations[1].merchant is None
    schema_path = Path(__file__).resolve().parents[4] / "contracts" / "schemas" \
        / "tbank-statement-result.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    assert list(Draft202012Validator(schema).iter_errors(result.to_payload())) == []


def test_parses_pdf_bytes_without_retaining_a_source_file(monkeypatch, tmp_path):
    text = """Справка о движении средств
01.10.2026
10:00
01.10.2026
10:00
-12.35 ₽
-12.35 ₽
Оплата в Магазин
1234
"""
    path = install_pdf_text(monkeypatch, tmp_path, [text])

    result = tbank_pdf.parse_tbank_pdf_bytes(path.read_bytes())

    assert result.operations[0].signed_amount == "-12.35"
    assert result.quality == "unverifiable"


def test_reports_mismatch_without_rounding_or_losing_signed_operations(monkeypatch, tmp_path):
    text = """Справка о движении средств
01.10.2026
10:00
01.10.2026
10:00
-12.35 ₽
-12.35 ₽
Оплата заказа Кофейня
5555
11,34 ₽Расходы:
0,00 ₽Пополнения:
"""
    path = install_pdf_text(monkeypatch, tmp_path, [text])

    result = tbank_pdf.parse_tbank_pdf(path)

    assert result.quality == "mismatch"
    assert result.parsed_expense_total == "12.35"
    assert result.expected_expense_total == "11.34"
    assert result.expected_income_total == "0.00"
    assert result.operations[0].signed_amount == "-12.35"


def test_missing_totals_are_unverifiable_and_zero_total_is_present(monkeypatch, tmp_path):
    no_totals = """Справка о движении средств
01.10.2026
10:00
01.10.2026
10:00
+20.00 ₽
+20.00 ₽
Пополнение счёта
—
"""
    path = install_pdf_text(monkeypatch, tmp_path, [no_totals])

    result = tbank_pdf.parse_tbank_pdf(path)

    assert result.quality == "unverifiable"
    assert result.expected_expense_total is None
    assert result.expected_income_total is None
    assert result.parsed_expense_total == "0.00"
    assert result.parsed_income_total == "20.00"

    zero_expenses = no_totals + "0,00 ₽Расходы:\n20,00 ₽Пополнения:\n"
    zero_path = install_pdf_text(monkeypatch, tmp_path, [zero_expenses])
    zero_result = tbank_pdf.parse_tbank_pdf(zero_path)
    assert zero_result.quality == "valid"
    assert zero_result.expected_expense_total == "0.00"


def test_rejects_impossible_operation_dates(monkeypatch, tmp_path):
    text = """Справка о движении средств
32.10.2026
10:00
32.10.2026
10:00
-20.00 ₽
-20.00 ₽
Оплата в Магазин
1234
"""
    path = install_pdf_text(monkeypatch, tmp_path, [text])

    with pytest.raises(tbank_pdf.StatementParseError) as error:
        tbank_pdf.parse_tbank_pdf(path)

    assert error.value.code == "invalid_operation"


def test_rejects_operation_amount_beyond_core_money_range(monkeypatch, tmp_path):
    text = """Справка о движении средств
01.10.2026
10:00
01.10.2026
10:00
-1000000000000000000.00 ₽
-1000000000000000000.00 ₽
Оплата в Магазин
1234
"""
    path = install_pdf_text(monkeypatch, tmp_path, [text])

    with pytest.raises(tbank_pdf.StatementParseError) as error:
        tbank_pdf.parse_tbank_pdf(path)

    assert error.value.code == "invalid_operation"


def test_parser_payload_matches_strict_versioned_import_contract():
    contract_path = Path(__file__).resolve().parents[4] / "contracts" / "schemas" \
        / "tbank-statement-result.v1.schema.json"
    schema = json.loads(contract_path.read_text(encoding="utf-8"))
    result = {
        "operations": [{
            "operationDate": "2026-10-01", "operationTime": "10:00", "signedAmount": "-12.35",
            "currency": "RUB", "kind": "purchase", "merchant": "Магазин",
            "description": "Покупка", "cardLast4": "1234",
        }],
        "parsedExpenseTotal": "12.35", "parsedIncomeTotal": "0.00",
        "expectedExpenseTotal": "12.35", "expectedIncomeTotal": "0.00",
        "quality": "valid", "periodStart": "2026-10-01", "periodEnd": "2026-10-01",
        "parseVersion": "tbank-pdf.v1",
    }

    validator = Draft202012Validator(schema)
    assert list(validator.iter_errors(result)) == []
    assert list(validator.iter_errors({**result, "unexpected": True}))
    assert list(validator.iter_errors({**result, "parsedExpenseTotal": 12.35}))


@pytest.mark.parametrize(("pages", "expected_code"), [
    (["Не справка банка"], "invalid_format"),
    ([None], "no_text"),
    (["Справка о движении средств\nИтогов и операций нет"], "no_operations"),
])
def test_explains_unsupported_scanned_and_empty_pdfs(monkeypatch, tmp_path, pages, expected_code):
    path = install_pdf_text(monkeypatch, tmp_path, pages)

    with pytest.raises(tbank_pdf.StatementParseError) as error:
        tbank_pdf.parse_tbank_pdf(path)

    assert error.value.code == expected_code
