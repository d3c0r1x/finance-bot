"""Цели на месяц: одна привычка, один измеримый шаг, честный исход.

Раньше цели жили в `services/advice.py` и составляли больше трети файла — вместе с
необязательными покупками, трендом, эффектом советов и пересчётом разборов. Теперь это
отдельный владелец: всё, что касается цели — кандидаты, ход, тексты, история, единицы
счёта, — меняется в одном файле.

Данные цели не дублируются: в настройках лежит запись с началом окна (`started_at`),
конец выводится из него (`goal_ends`), а ход считается по сохранённым вердиктам чеков и
истории покупок. Итог закончившейся цели попадает в историю в единственном месте —
`close_goal_if_finished`, — а отметку «итог отправлен» ставит планировщик после успешной
отправки (`mark_goal_outcome_sent`), поэтому пропущенная сводка итог не съедает.

Кандидаты берутся из «не брать» (`services.advice.banned`): цель — обещание, и строить её
на догадке модели нечестно. Из advice импортируются только проверенные списки и константы —
второго расчёта порогов здесь нет.
"""
import json
import re
from datetime import datetime, timedelta

from database.db import get_setting, set_setting
from services.purchase_history import grouped_entries, parse_date, product_key
from utils.formatting import format_amount, md_safe, plural_ru

from services.advice import banned, DAYS_IN_MONTH, waste_groups  # noqa: F401 — база кандидатов

# Ключ настроек: одна цель на месяц — что и сколько раз брать вместо привычного.
GOAL_KEY = "advice:goal:{user_id}"
# Меньше двух покупок в месяц — это не привычка, а случай: цель по такому товару бессмысленна.
GOAL_MIN_MONTHLY = 2.0
# Пометка в записи цели: итог закончившейся цели человеку уже отправлен.
GOAL_ANNOUNCED_FIELD = "announced_at"
# Ключ настроек: итоги закончившихся целей — новые первыми.
GOAL_HISTORY_KEY = "advice:goal_history:{user_id}"
# Сколько итогов хранить: глубже года это уже архив, а не «как у меня вообще с целями».
GOAL_HISTORY_LIMIT = 24
# Сколько итогов показывать списком — остальное только числом.
GOAL_HISTORY_SHOWN = 5
# Пометка в записи цели: итог уже записан в историю (ставится один раз).
GOAL_CLOSED_FIELD = "closed_at"
# Две формы одного шага: «не чаще N раз» и «не больше X ₽». Это одна и та же цель в разных
# единицах, поэтому единица — предпочтение человека (`GOAL_UNIT_KEY`), а не свойство товара:
# иначе на каждый товар было бы по две кнопки и по два одинаковых обещания.
GOAL_COUNT, GOAL_SUM = "count", "sum"
GOAL_UNITS = (GOAL_COUNT, GOAL_SUM)
# Категорийная цель: «на сладкое уходит 4 200 ₽» складывается из мороженого, шоколада и
# печенья — по отдельности ни один из них цели не заслуживает. Группа — не налог на
# категорию чека: это узкие кластеры того, что разбор стабильно советует не брать.
CATEGORY_GOAL_KEY = "advice:goal_category:{user_id}"
CATEGORY_PREFIX = "cat:"
GOAL_CATEGORIES: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    # (ключ, имя для человека, стемы названий позиций). Стем сравнивается с НАЧАЛОМ слова:
    # подстрока ловила бы «шоколад» как «кола» и «колбаса» как «кола» тоже. Точное слово
    # помечается «!» — «Кола» и «Колбаса» различаются только целиком.
    ("сладкое", "Сладкое", ("шоколад", "конфет", "карамел", "ирис", "зефир", "халв",
                            "печень", "пряник", "вафл", "рулет", "торт",
                            "пирог", "пончик", "мармелад", "пастил", "суфле",
                            "морожен", "эскимо", "джем", "варень", "сникерс",
                            "баунти", "твикс")),
    ("снеки", "Снеки и чипсы", ("чипс", "сухарик", "снек", "попкорн", "соломк",
                                "кириешки", "начос", "арахис", "фисташ", "кукуруз",
                                "взлет")),
    ("сладкие напитки", "Сладкие напитки", ("кока", "пепси", "спрайт", "фанта",
                                            "лимонад", "энергет", "адреналин",
                                            "байкал", "таранто", "газирова", "juice",
                                            "морс", "кола!", "сок!", "нектар!")),
    ("фастфуд", "Фастфуд и перекусы", ("бургер", "шаурм", "пицца", "наггетс", "фри",
                                       "хот-дог", "хотдог", "доширак", "роллтон",
                                       "ролл", "лапша")),
    ("пакеты", "Пакеты и упаковка", ("пакет", "упаковк", "фольг", "плен", "скотч")),
)
# Ключ настроек: в какой единице человек считает шаг цели.
GOAL_UNIT_KEY = "advice:goal_unit:{user_id}"
# Шаг меньше сотни рублей в месяц человек не заметит: цель стала бы формальностью.
GOAL_MIN_MONEY_STEP = 100.0


def goal_ends(started: datetime) -> datetime:
    """Момент, до которого считается цель: месяц от дня, когда её взяли.

    Не «до конца календарного месяца»: цель, взятая 29-го, превращалась бы в цель на два
    дня, а покупка, сделанная до обещания, задним числом шла бы в счёт. Тридцать дней
    с сегодня — та же единица, в которой измерена обычная частота товара, поэтому шаг
    «не чаще N раз» остаётся сравним с ней при любой дате постановки.
    """
    return started + timedelta(days=DAYS_IN_MONTH)


def goal_target(monthly: float) -> int:
    """Сколько раз в месяц брать вместо привычного: примерно вдвое меньше, но хотя бы раз.

    Не «на один меньше»: у привычки шесть раз в месяц такое послабление ничего не меняет.
    И не ноль: совсем не брать товар — это не цель, а запрет, и он не выполняется.
    """
    return max(1, round(monthly / 2))


def goal_money_step(spend: float, usual: float) -> int | None:
    """Денежный шаг: примерно вдвое меньше обычных трат, но не меньше одной покупки.

    Округление до читаемого числа — 10 ₽ до тысячи и 50 ₽ выше: «не больше 1 250 ₽» человек
    запомнит, «1 247,83 ₽» — нет. Пол по цене одной покупки не косметика: шаг ниже неё
    превращал бы цель в «не покупай вовсе», а это запрет, и он не выполняется. Если и после
    этого экономия меньше `GOAL_MIN_MONEY_STEP`, денежная форма не предлагается вовсе —
    формальность вместо цели хуже, чем её отсутствие.
    """
    if spend <= 0 or usual <= 0:
        return None
    half = spend / 2
    rounded = round(half / 10) * 10 if half < 1000 else round(half / 50) * 50
    step = max(float(rounded), round(usual))
    if step >= spend or spend - step < GOAL_MIN_MONEY_STEP:
        return None
    return int(step)


def _purchase_entries(history, key: str) -> list[dict]:
    """Покупки одного товара по всей истории — тем же ключом, что в «не брать» и каталоге."""
    if not key:
        return []
    found = []
    for group in grouped_entries(history):
        found += [entry for entry in group["entries"]
                  if product_key(entry.get("name") or "") == key]
    return found


def _monthly_rate(entries: list[dict], floor_days: int = DAYS_IN_MONTH) -> float:
    """Сколько раз в месяц товар берут обычно — по всем его покупкам, не по вердиктам.

    Делим на фактический промежуток между первой и последней покупкой, но не меньше месяца:
    взятый трижды за неделю товар — это «три раза за месяц», а не «тринадцать», короткая
    история не должна раздувать частоту. Верхнего предела нет намеренно: привычка «раз в
    10 дней» — это 3 раза в месяц, и деление на всё окно (90 дн.) превращало бы её в 1,3,
    из-за чего цель не предлагалась бы именно тем товарам, ради которых она и нужна.
    """
    if not entries:
        return 0.0
    first = parse_date(entries[0].get("date"))
    last = parse_date(entries[-1].get("date"))
    if first is None or last is None:
        return round(len(entries) / floor_days * DAYS_IN_MONTH, 2)
    span = max((last - first).days, floor_days)
    return round(len(entries) / span * DAYS_IN_MONTH, 2)


async def goal_unit(user_id: int) -> str:
    """В какой единице человек считает шаг цели. Незнакомое значение — раза, не падение."""
    stored = (await get_setting(GOAL_UNIT_KEY.format(user_id=user_id), "") or "").strip()
    return stored if stored in GOAL_UNITS else GOAL_COUNT


async def set_goal_unit(user_id: int, unit: str) -> None:
    """Запомнить единицу счёта. Поставленную цель это не переписывает — она считается в своей."""
    await set_setting(GOAL_UNIT_KEY.format(user_id=user_id),
                      unit if unit in GOAL_UNITS else GOAL_COUNT)


def goal_candidates(rows, history, allowed: set[str] | None = None,
                    confirmed: set[str] | None = None, limit: int = 3,
                    unit: str = GOAL_COUNT) -> list[dict]:
    """Что бот предложит сократить: привычки, про которые говорит не только модель.

    Кандидаты берутся из того же списка, что «не брать»: два необязательных разбора и больше,
    без догадок модели (цель — обещание, и строить её на догадке нечестно). Товар должен
    покупаться регулярно — по редким покупкам цель не измерить — и браться по той же истории
    чеков, что видит каталог цен, а не по вердиктам: иначе частоту было бы не сравнить.

    Шаг описывается в выбранной единице (`unit`): в разах — «не чаще N раз», в деньгах —
    «не больше X ₽». Второе число тоже считается и уезжает в карточку: это тот же шаг в
    других единицах, и человек имеет право видеть оба, даже если считает по одному.
    Товар, у которого в выбранной единице шага нет (в деньгах: дешёвый, экономия меньше
    `GOAL_MIN_MONEY_STEP`), попадает в `skipped` со своей строкой — иначе после переключения
    единицы список молча становится короче, и человек не понимает, куда делся товар.
    """
    props = [entry for entry in banned(rows, allowed, confirmed=confirmed)
             if not entry["guess"]]
    found = []
    skipped = []
    for entry in props:
        entries = _purchase_entries(history, entry["key"])
        monthly = _monthly_rate(entries)
        if monthly < GOAL_MIN_MONTHLY:
            continue
        target = goal_target(monthly)
        if target >= round(monthly):
            continue
        usual = round(sum(float(item.get("sum") or 0) for item in entries[:6]) /
                      max(1, len(entries[:6])), 2)
        spend = round(monthly * usual, 2)          # обычные траты на товар за месяц
        money = goal_money_step(spend, usual)
        if money is None:
            # В деньгах шага нет: товаров с правилом на борту немного, и молча потерять
            # один из них при переключении единицы — значит спрятать ответ на вопрос,
            # «куда делся пакет из списка». В разах товар остаётся как обычно.
            if unit == GOAL_SUM:
                skipped.append({"name": entry["name"], "spend": spend})
            else:
                money = 0
        saving = (round(spend - money, 2) if unit == GOAL_SUM and money
                  else round(usual * (monthly - target), 2))
        if unit == GOAL_SUM and not money:
            continue
        found.append({"key": entry["key"], "name": entry["name"], "monthly": monthly,
                      "unit": unit, "target": target if unit == GOAL_COUNT else 0,
                      "limit": money if unit == GOAL_SUM else (money or 0),
                      "baseline": round(monthly, 1), "spend": spend,
                      "usual": usual, "count": entry["count"],
                      "sum": entry["sum"], "saving": saving,
                      "title": entry["title"], "advice": entry["advice"]})
    found.sort(key=lambda item: (-item["saving"], -item["count"]))
    found_skipped = skipped[:3]
    return found[:limit], found_skipped


def parse_goal(raw: str | None) -> dict | None:
    """Разбор сохранённой цели — один парсер на бота и панель."""
    if not raw:
        return None
    try:
        goal = json.loads(raw)
    except (TypeError, ValueError):
        return None
    if not isinstance(goal, dict) or not goal.get("key"):
        return None
    started = parse_date(goal.get("started_at"))
    if started is None:
        return None
    # Единица счёта: у записей до её появления — раза, а не «неизвестно»: тогда шаг был по
    # числу раз, и старая цель должна читаться так же, как читалась. Категорийная цель всегда
    # в разах: деньги между конкретными товарами группы делить нечестно.
    kind = goal.get("unit") if goal.get("unit") in GOAL_UNITS else GOAL_COUNT
    if str(goal["key"]).startswith(CATEGORY_PREFIX):
        kind = GOAL_COUNT
    # Конец цели выводится из начала, а не хранится: одна дата в базе — один хозяин окна.
    return {**goal, "unit": kind, "started": started, "ends": goal_ends(started)}


async def stored_goal(user_id: int) -> dict | None:
    """Действующая цель пользователя, если она есть."""
    return parse_goal(await get_setting(GOAL_KEY.format(user_id=user_id), ""))


# ─── Категорийные цели: «на сладкое уходит 4 200 ₽ в месяц» ──────────────

def _name_matches_category(name: str, stems: tuple[str, ...]) -> bool:
    """Позиция принадлежит категории: слово названия начинается со стема.

    Стем сверяется с началом слова, а не с подстрокой: иначе «шоколад» попадает в
    «Сладкие напитки» через «кола» внутри слова. Точное слово помечается «!»: «Кола»
    и «Колбаса» различаются только целым словом, любой их общий префикс — это «кола».
    """
    lowered = (name or "").lower().replace("ё", "е")
    words = re.findall(r"[a-zа-я0-9-]+", lowered)
    if not words:
        return False
    for stem in stems:
        if stem.endswith("!"):
            wanted = stem[:-1]
            if any(word == wanted for word in words):
                return True
        elif any(word.startswith(stem) for word in words):
            return True
    return False


def _category_entries(history, stems: tuple[str, ...]) -> list[dict]:
    """Покупки категории: каждая позиция чека, чьё название попало в группу."""
    found = []
    for group in grouped_entries(history):
        for entry in group["entries"]:
            if _name_matches_category(entry.get("name") or "", stems):
                found.append(entry)
    found.sort(key=lambda item: str(item.get("date") or ""))
    return found


def category_candidates(rows, history, allowed: set[str] | None = None,
                        confirmed: set[str] | None = None) -> list[dict]:
    """Категории, которым та же история честно позволяет предложить цель.

    Требования те же, что у товарной цели, и проверяются по тем же данным: разбор называл
    позиции этой категории необязательными минимум дважды (`banned`, без догадок модели),
    берут их не реже двух раз в месяц, и цель — примерно вдвое реже привычки. Разница в том,
    что обещание даётся всей группе: «на сладкое — вдвое реже», а не трём отдельным товарам.

    Денежная единица у категории не предлагается: сумму шага пришлось бы резать между
    конкретными товарами, которых в группе может и не быть в следующем месяце. Шаг в разах
    честен: покупок в месяц он не переписывает.
    """
    # Товары, разрешённые человеком к напоминаниям, в цели не идут: разрешение — решение
    # не трогать привычку, и обходить его категорией было бы обманом.
    allowed = allowed or set()
    confirmed = confirmed or set()
    # Пометка «необязательно» требуется два раза НА ГРУППУ, а не на один товар: смысл
    # категорийной цели в том, что сладкое каждый раз разное — мороженое, шоколад, печенье —
    # и требовать повтора одного товара значило бы отменить саму идею группы. Догадки модели
    # по-прежнему не участвуют: обещание строится только на правилах и подтверждённых товарах.
    marked = [entry for entry in waste_groups(rows, allowed, min_bans=1)
              if not entry["guess"] or entry["key"] in confirmed]
    found = []
    for cat_key, cat_name, stems in GOAL_CATEGORIES:
        members = [entry for entry in marked
                   if _name_matches_category(entry["name"], stems)
                   and entry["key"] not in allowed]
        if sum(entry["count"] for entry in members) < 2:
            continue
        entries = _category_entries(history, stems)
        monthly = _monthly_rate(entries)
        if monthly < GOAL_MIN_MONTHLY:
            continue
        target = goal_target(monthly)
        if target >= round(monthly):
            continue
        usual = round(sum(float(item.get("sum") or 0) for item in entries[:6]) /
                      max(1, len(entries[:6])), 2)
        spend = round(monthly * usual, 2)
        names = sorted({entry["name"] for entry in members}, key=len)
        found.append({"key": CATEGORY_PREFIX + cat_key, "name": cat_name,
                      "monthly": monthly, "unit": GOAL_COUNT, "target": target,
                      "limit": 0, "baseline": round(monthly, 1), "usual": usual,
                      "spend": spend, "saving": round(spend - usual * target, 2),
                      "count": sum(entry["count"] for entry in members),
                      "sum": round(sum(entry["sum"] for entry in members), 2),
                      "title": members[0]["title"],
                      "advice": ", ".join(sorted({entry["advice"] for entry in members
                                                  if entry["advice"]})[:2]),
                      # Полный состав: он нужен фильтру дублей, а на экране показываются
                      # первые четыре названия.
                      "members": names})
    found.sort(key=lambda item: (-item["saving"], -item["count"]))
    return found[:2]


def category_members(goal: dict) -> tuple[str, ...]:
    """Стемы категории по ключу цели: пусто для товарной цели — ей это не нужно."""
    if not str(goal.get("key") or "").startswith(CATEGORY_PREFIX):
        return ()
    wanted = str(goal["key"])[len(CATEGORY_PREFIX):]
    return next((stems for key, _name, stems in GOAL_CATEGORIES if key == wanted), ())


def category_purchase_note(goal: dict, progress: dict | None,
                           items: list[dict]) -> str:
    """Строка в карточке покупки: только что купленное попало в категорийную цель.

    Без неё категорийная цель молчала бы месяц: на экране она есть, а в момент, когда
    человек кладёт в корзину мороженое, бот о ней не говорит — и обещание работает,
    только пока его помнишь. Товарная цель напоминает через repeat_warnings; здесь тот
    же момент, но матч по группе, а не по названию товара.
    """
    stems = category_members(goal)
    if not stems or not items:
        return ""
    hits = sorted({str(item.get("name") or "").strip()
                   for item in items if _name_matches_category(item.get("name") or "", stems)
                   and str(item.get("name") or "").strip()})
    if not hits:
        return ""
    names = ", ".join(md_safe(name) for name in hits[:3])
    more = f" и ещё {len(hits) - 3}" if len(hits) > 3 else ""
    base = f"🎯 В цели «{md_safe(goal['name'])}» засчитано: {names}{more}"
    if progress:
        done = _goal_done_phrase(progress)
        base += f" — {done}"
        if progress["over"]:
            base += ", шаг уже превышен"
    return base + "."


async def set_goal(user_id: int, candidate: dict | None) -> None:
    """Ставит цель или убирает её (пустое значение — снять).

    Цель живёт месяц с сегодняшнего дня: так шаг сравним с обычной частотой товара (она тоже
    месячная) и не зависит от того, взята цель 1-го числа или 29-го. Единица счёта берётся
    у кандидата: в ней же цель и будет считаться до конца, поэтому смена предпочтения потом
    уже поставленную цель не переписывает.
    """
    if candidate is None:
        await set_setting(GOAL_KEY.format(user_id=user_id), "")
        return
    unit = candidate.get("unit") if candidate.get("unit") in GOAL_UNITS else GOAL_COUNT
    if unit == GOAL_SUM and not candidate.get("limit"):
        unit = GOAL_COUNT   # денежного шага нет — считать его нулём было бы обманом
    record = {"key": candidate["key"], "name": candidate["name"], "unit": unit,
              "baseline": candidate.get("baseline") or candidate.get("monthly") or 0,
              "usual": candidate.get("usual") or 0,
              "spend": candidate.get("spend") or 0,
              "started_at": datetime.now().isoformat(sep=" ", timespec="seconds")}
    # Категорийная цель хранит названия товаров, на которых построена: экран честен, даже
    # когда история выросла, а если цель убрана и поставлена заново — состав пересчитается.
    if candidate.get("members"):
        record["members"] = list(candidate["members"])
    # Хранятся оба числа: по одному цель считается, по другому показывается как то же самое
    # в других единицах. Считает `goal_progress` всегда по той, что выбрана при постановке.
    record["target"] = int(candidate.get("target") or goal_target(record["baseline"]))
    record["limit"] = int(candidate.get("limit") or 0)
    await set_setting(GOAL_KEY.format(user_id=user_id),
                      json.dumps(record, ensure_ascii=False))


def goal_progress(goal: dict | None, history, today: datetime | None = None) -> dict | None:
    """Что уже куплено с начала месяца по цели и что это значит в деньгах."""
    if not goal:
        return None
    now = today or datetime.now()
    # Верхняя граница — сегодня, а не конец месяца: чек с датой из будущего (или переставленные
    # часы) не должен заранее съедать цель. Для закончившегося месяца сегодня уже позже конца,
    # поэтому граница совпадает с ним и исход месяца считается по всем его покупкам.
    until = min(goal["ends"], now)
    stems = category_members(goal)
    in_window = [entry for entry in (_category_entries(history, stems) if stems
                                     else _purchase_entries(history, goal["key"]))
                 if (moment := parse_date(entry.get("date"))) and goal["started"] <= moment <= until]
    bought = len(in_window)
    spent = round(sum(float(entry.get("sum") or 0) for entry in in_window), 2)
    unit = goal.get("unit") or GOAL_COUNT
    target = int(goal.get("target") or 0)
    limit = float(goal.get("limit") or 0)
    usual, baseline = round(float(goal.get("usual") or 0), 2), round(
        float(goal.get("baseline") or 0), 2)
    spend = round(float(goal.get("spend") or 0), 2)
    if unit == GOAL_SUM:
        # Цель в деньгах: перерасход и остаток считаются по суммам, а не по числу покупок.
        over, met = spent > limit, spent <= limit
        saved = max(0.0, round(spend - spent, 2))
    else:
        over, met = bought > target, bought <= target
        saved = round(usual * max(0, round(baseline) - bought), 2)
    return {"unit": unit, "bought": bought, "target": target, "limit": limit, "spent": spent,
            "over": over, "finished": now > goal["ends"],
            "days_left": max(0, (goal["ends"] - now).days), "met": met,
            "usual": usual, "normal": baseline, "usual_month": spend,
            "window": f"{goal['started']:%d.%m}–{goal['ends']:%d.%m}", "saved": saved}


def _goal_step_phrase(progress: dict) -> str:
    """Шаг цели словами: «не чаще 2 раз в месяц» или «не больше 300 ₽ в месяц»."""
    if progress.get("unit") == GOAL_SUM:
        return f"не больше {format_amount(progress['limit'])} в месяц"
    times = plural_ru(progress["target"], "раз", "раза", "раз")
    return f"не чаще {progress['target']} {times} в месяц"


def _goal_done_phrase(progress: dict) -> str:
    """Что уже набрано — той же парой чисел, что и шаг: «2 из 3» или «450 ₽ из 300 ₽»."""
    if progress.get("unit") == GOAL_SUM:
        return f"{format_amount(progress['spent'])} из {format_amount(progress['limit'])}"
    return f"{progress['bought']} из {progress['target']}"


def _goal_normal_phrase(progress: dict, past: bool = False) -> str:
    """Как было обычно — в тех же единицах, что и шаг: иначе сравнивать не с чем."""
    if progress.get("unit") == GOAL_SUM:
        word = "уходило" if past else "уходит"
        return f"обычно {word} {format_amount(progress['usual_month'])} в месяц"
    was = "была " if past else ""
    return f"обычная частота {was}{_frequency_text(progress['normal'])} в месяц"


def goal_equivalent(goal: dict, progress: dict) -> str:
    """Тот же шаг в других единицах — строка экрана цели: шаг один, а увидеть его можно двояко."""
    if not goal or not progress:
        return ""
    if progress.get("unit") == GOAL_SUM:
        if not progress["target"]:
            return ""
        times = plural_ru(progress["target"], "раз", "раза", "раз")
        return (f"Это примерно не чаще {progress['target']} {times} в месяц: "
                "считаю по деньгам, но обещание то же.")
    if not goal.get("limit"):
        return ""
    return (f"Это примерно не больше {format_amount(goal['limit'])} в месяц: "
            "считаю по разам, но обещание то же.")


def goal_line(goal: dict | None, progress: dict | None, today: datetime | None = None) -> str:
    """Короткая строка цели — одна на дайджест, панель и экран цели."""
    if not goal or not progress:
        return ""
    name = md_safe(goal["name"])
    step, done = _goal_step_phrase(progress), _goal_done_phrase(progress)
    if progress["finished"]:
        # Окно называется датами: цель идёт месяц с дня постановки, а не «весь сентябрь».
        if progress["met"]:
            tail = (f" это ~{format_amount(progress['saved'])} в месяц, которые не ушли"
                    if progress["saved"] else " цель сдержана")
            return f"🎯 Цель ({progress['window']}): «{name}» — {done} — выполнена.{tail}."
        return (f"🎯 Цель ({progress['window']}): «{name}» — {done} — не вышло, "
                f"{_goal_normal_phrase(progress, past=True)}. "
                "Это не упрёк: цель без провалов не бывает.")
    head = (f"🎯 Цель до {goal['ends']:%d.%m}: «{name}» — {step}, "
            f"{_goal_normal_phrase(progress)}.")
    tail = f" Пока {done}, осталось {progress['days_left']} дн."
    if progress["over"]:
        tail = (f" Уже {done}: цель не сдержится, "
                "но это не приговор — этот месяц просто такой.")
    return head + tail


def goal_digest_line(goal: dict | None, progress: dict | None,
                     today: datetime | None = None) -> str:
    """Что о цели говорит сводка: ход — всегда, итог — ровно один раз.

    Без этой отметки итог либо терялся, либо повторялся: сводка приходит раз в неделю, и по
    одной лишь давности нельзя отличить «итог ещё не сказан» от «уже сказан». Отметку
    (`announced_at`) ставит планировщик после успешной отправки (`mark_goal_outcome_sent`),
    поэтому пропущенная отправка итог не съедает: цель и панель показывают его всегда.
    """
    if not goal or not progress:
        return ""
    if progress["finished"] and goal.get(GOAL_ANNOUNCED_FIELD):
        return ""
    return goal_line(goal, progress, today)


async def mark_goal_outcome_sent(user_id: int) -> None:
    """Отметить, что итог закончившейся цели человеку отправлен — после самой отправки.

    Незакончившаяся цель не отмечается: иначе отметка съела бы будущий итог.
    """
    from database.db import get_receipt_price_history

    goal = await stored_goal(user_id)
    if not goal or goal.get(GOAL_ANNOUNCED_FIELD):
        return
    if not goal_progress(goal, await get_receipt_price_history(user_id))["finished"]:
        return
    await set_setting(GOAL_KEY.format(user_id=user_id), json.dumps(_goal_record(
        goal, **{GOAL_ANNOUNCED_FIELD: datetime.now().isoformat(sep=" ",
                                                              timespec="seconds")}),
        ensure_ascii=False))


def _goal_record(goal: dict, **extra) -> dict:
    """Запись цели для настроек: `started` и `ends` в базе не лежат — они выводятся из начала."""
    fresh = {key: value for key, value in goal.items() if key not in ("started", "ends")}
    return {**fresh, **extra}


def parse_goal_history(raw: str | None) -> list[dict]:
    """Разбор сохранённой истории целей — один парсер на бота и панель.

    Мусор в записи (не JSON, не список, записи без названия) читается как пустая история,
    а не как падение экрана: историю могли испортить руками или она осталась от опытов.
    """
    if not raw:
        return []
    try:
        entries = json.loads(raw)
    except (TypeError, ValueError):
        return []
    if not isinstance(entries, list):
        return []
    return [entry for entry in entries if isinstance(entry, dict) and entry.get("name")]


async def goal_history(user_id: int) -> list[dict]:
    """Итоги закончившихся целей, новые первыми."""
    return parse_goal_history(await get_setting(GOAL_HISTORY_KEY.format(user_id=user_id), ""))


def goal_history_entry(goal: dict, progress: dict,
                       today: datetime | None = None) -> dict:
    """Один итог для истории: те же числа, что в строке цели, но без форматирования."""
    now = today or datetime.now()
    return {"key": goal["key"], "name": goal["name"], "unit": progress["unit"],
            "target": progress["target"], "limit": progress["limit"],
            "bought": progress["bought"], "spent": progress["spent"],
            "met": progress["met"], "saved": progress["saved"],
            "window": progress["window"],
            "started_at": goal["started"].isoformat(sep=" "),
            "closed_at": now.isoformat(sep=" ", timespec="seconds")}


async def close_goal_if_finished(user_id: int, today: datetime | None = None) -> dict | None:
    """Записать итог закончившейся цели в историю — ровно один раз.

    Это единственное место, где цель становится историей, и вызывается оно там, где бот
    вообще узнаёт об окончании: в обеих задачах планировщика и на экране цели. Отметка
    `closed_at` лежит в самой записи цели, поэтому вызов из нескольких мест не задвоит итог
    и повторный запуск ничего не перепишет. Незакончившаяся цель не закрывается — у неё
    ещё есть ход, и хоронить её заранее было бы неправдой.
    """
    from database.db import get_receipt_price_history

    goal = await stored_goal(user_id)
    if not goal or goal.get(GOAL_CLOSED_FIELD):
        return None
    progress = goal_progress(goal, await get_receipt_price_history(user_id), today=today)
    if not progress["finished"]:
        return None
    entry = goal_history_entry(goal, progress, today=today)
    entries = await goal_history(user_id)
    await set_setting(GOAL_HISTORY_KEY.format(user_id=user_id),
                      json.dumps([entry, *entries][:GOAL_HISTORY_LIMIT], ensure_ascii=False))
    await set_setting(GOAL_KEY.format(user_id=user_id), json.dumps(
        _goal_record(goal, **{GOAL_CLOSED_FIELD: entry["closed_at"]}), ensure_ascii=False))
    return entry


def goal_history_line(entries: list[dict]) -> str:
    """Сколько обещаний сдержано — одна строка на дайджест, экран цели и панель.

    Счёт идёт по всему хранимому списку, а не по последним пяти: это «как у меня вообще
    с целями». Если список дорос до предела, об этом говорится прямо — иначе «из 24»
    читалось бы как «за всё время».
    """
    if not entries:
        return ""
    met = sum(1 for entry in entries if entry.get("met"))
    tail = (f" Из более ранних целей вижу только {GOAL_HISTORY_LIMIT}: глубже бот не хранит."
            if len(entries) >= GOAL_HISTORY_LIMIT else "")
    return f"🏁 Сдержано {met} из {len(entries)} целей.{tail}"


def goal_report_line(goal: dict | None, progress: dict | None, entries: list[dict],
                     today: datetime | None = None) -> str:
    """Строка о цели для обычного отчёта: ход активной цели или счёт по прошлым.

    Месячный отчёт — то место, где человек и так смотрит на месяц, поэтому цель попадает и
    туда, а не живёт только на собственном экране. Формулировки берутся у тех же `goal_line`
    и `goal_history_line`, что в дайджесте и панели: здесь только выбор, что уместно, —
    второй формулировки тех же чисел не заводится. Если целей не было вовсе, отчёт молчит.
    """
    return goal_line(goal, progress, today) or goal_history_line(entries)


def goal_history_text(entries: list[dict], limit: int = GOAL_HISTORY_SHOWN) -> str:
    """История целей словами: что было, чем кончилось и что это в деньгах."""
    if not entries:
        return ""
    lines = [goal_history_line(entries)]
    for entry in entries[:limit]:
        # Числа читаются через `.get`: запись могла остаться от старой версии или быть
        # поправленной руками, и одна кривая строка не должна ронять весь экран. Единица
        # берётся из записи: цель в деньгах и цель в разах читаются по-разному, и показать
        # их одним шаблоном значило бы перепутать 300 ₽ с тремя покупками.
        if entry.get("unit") == GOAL_SUM:
            done = (f"{format_amount(entry.get('spent') or 0)} из "
                    f"{format_amount(entry.get('limit') or 0)}")
        else:
            done = f"{entry.get('bought') or 0} из {entry.get('target') or 0}"
        if entry.get("met"):
            tail = (f"выполнена ({done}), ~{format_amount(entry['saved'])} не ушли"
                    if entry.get("saved") else f"выполнена ({done})")
        else:
            tail = f"не вышло ({done})"
        lines.append(f"   • {md_safe(entry['name'])} — {tail}, {entry.get('window', '')}.")
    if len(entries) > limit:
        lines.append(f"   … и ещё {len(entries) - limit}.")
    lines.append("Цель — ориентир: срыв не значит, что ты сделал что-то не так. "
                 "Бот считает только то, что видно в чеках.")
    return "\n".join(lines)


def goal_text(goal: dict | None, progress: dict | None, today: datetime | None = None,
              entries: list[dict] | None = None) -> str:
    """Экран цели: сама цель, как её считают и как её убрать."""
    line = goal_line(goal, progress, today)
    if not line:
        return ""
    lines = [line, "", f"Считаю по чекам: покупки «{md_safe(goal['name'])}» с "
                        f"{goal['started']:%d.%m} по {goal['ends']:%d.%m}, "
                        f"в этом окне потрачено {format_amount(progress['spent'])}."]
    if goal.get("members"):
        # Категория без состава читалась бы как запрет всей еды: человек должен видеть,
        # какие именно товары наблюдаются — состав подсказывает и спор, если он не тот.
        lines.append("В группу входят: " + ", ".join(md_safe(m) for m in goal["members"]) + ".")
    # Шаг один, но увидеть его можно в обеих единицах: кто-то считает разами, кто-то деньгами.
    equivalent = goal_equivalent(goal, progress)
    if equivalent:
        lines.append(equivalent)
    if progress["finished"]:
        lines.append("Цель закончилась: можно взять новую или убрать старую.")
        if entries:
            # Итог без истории читался бы как единственный: рядом видно, как было раньше.
            lines += ["", goal_history_text(entries)]
    else:
        lines.append("Цель — ориентир, а не запрет: если она мешает, её можно убрать.")
    return "\n".join(lines)


GOAL_NOTHING = ("Пока нечего предложить: цель ставится по привычке — товару, который разбор "
                "необязательным называл дважды и больше, а ты берёшь его регулярно. "
                "Отправь новые чеки, и здесь появится, что сократить.")
GOAL_NOTHING_MONEY = ("В деньгах по этим товарам шага нет: траты маленькие, и обещание "
                      "«не больше 50 ₽» было бы формальностью. Считай в разах — кнопка ниже.")


def _frequency_text(rate: float) -> str:
    """Частота словами: целая — «2 раза», дробная — «3,3 раза» (не «3.3 раз»).

    Дробное число в русском требует родительного падежа единственного числа, а точка —
    след английской печати: проект везде пишет запятую.
    """
    if float(rate).is_integer():
        times = plural_ru(int(rate), "раз", "раза", "раз")
        return f"{int(rate)} {times}"
    return f"{float(rate):.1f}".replace(".", ",") + " раза"


def goal_step_phrase(item: dict) -> str:
    """Шаг кандидата словами — в его единице и с обычным значением рядом, чтобы было с чем сравнить."""
    if item.get("unit") == GOAL_SUM:
        return (f"не больше {format_amount(item['limit'])} в месяц "
                f"(обычно уходит ~{format_amount(item['spend'])})")
    times = plural_ru(item["target"], "раз", "раза", "раз")
    return f"не чаще {item['target']} {times} в месяц (обычно {_frequency_text(item['baseline'])})"


def _candidate_lines(candidates: list[dict]) -> list[str]:
    """Строки про кандидатов — одни и те же на экране предложений и на итоге цели."""
    lines: list[str] = []
    for item in candidates:
        lines += ["", f"▪️ **{md_safe(item['name'])}** — {goal_step_phrase(item)}."]
        if item.get("members"):
            # Категория без состава похожа на запрет всей еды: состав же показывает,
            # что цель про конкретные привычки, и у товара в списке остаётся контекст.
            lines.append(f"   Из разборов: {md_safe(', '.join(item['members']))}.")
        if item.get("saving"):
            lines.append(f"   Это до ~{format_amount(item['saving'])} в месяц.")
        if item.get("advice"):
            lines.append(f"   Разбор говорил: {md_safe(item['advice'])}")
    return lines


def goal_unit_text(unit: str) -> str:
    """В какой единице считается шаг и как это переключить — одной строкой под предложениями."""
    if unit == GOAL_SUM:
        return ("Считаю **в деньгах**: шаг — сумма в месяц. Это та же цель в других единицах: "
                "«не больше 600 ₽» это и есть «вдвое реже». Переключить можно кнопкой ниже.")
    return ("Считаю **в разах**: шаг — сколько раз за месяц. Это та же цель в других единицах: "
            "«вдвое реже» это и есть «не больше половины денег». Переключить можно кнопкой ниже.")


def _skipped_text(skipped: list[dict]) -> str:
    """Куда делись товары, у которых денежного шага нет — вместо молчаливой пропажи.

    После переключения в деньги список короче, и без объяснения человек решает, что бот
    потерял товар. Достаточно назвать его и почему: дешёвый товар цели не заслуживает.
    """
    if not skipped:
        return ""
    names = ", ".join(md_safe(item["name"]) for item in skipped[:2])
    more = f" и ещё {len(skipped) - 2}" if len(skipped) > 2 else ""
    return (f"Показаны не все: по {names}{more} шага в деньгах нет — в месяц уходит "
            "слишком мало, цель была бы формальностью.")


def goal_proposals_text(candidates: list[dict], unit: str = GOAL_COUNT,
                        skipped: list[dict] | None = None) -> str:
    """Что бот предлагает взять целью — с числами, а не «сократите сладкое».

    Список может быть короче привычек не молча: товары без шага в выбранной единице
    названы под списком (`skipped`), а пустой экран в деньгах говорит про саму единицу,
    а не отправляет за новыми чеками — новые чеки того же дешёвого товара не помогут.
    """
    if not candidates:
        nothing = GOAL_NOTHING_MONEY if (unit == GOAL_SUM and skipped) else GOAL_NOTHING
        return f"🎯 **Цель на месяц**\n\n{nothing}"
    lines = ["🎯 **Цель на месяц**", "",
             "Одна цель и один измеримый шаг — вместо списка «ешь полезнее». "
             "Выбери, что сократить: считаю по чекам, через месяц скажу, вышло или нет. "
             "Шаг задаётся в той единице, которая тебе понятнее — в разах или в деньгах."]
    if any(item.get("members") for item in candidates):
        # Категорийная цель сама объясняет, зачем она в списке товарных: по одному
        # мороженому цели могло не быть, а группа их собирает.
        lines.append("Категории собраны из товаров, которые разбор помечал необязательными: "
                     "по отдельности каждый из них цели не дотягивал.")
    lines += _candidate_lines(candidates)
    if skipped:
        lines.append("")
        lines.append(_skipped_text(skipped))
    lines += ["", goal_unit_text(unit)]
    return "\n".join(lines)


def goal_followup_text(candidates: list[dict]) -> str:
    """Что взять следующей целью — на экране, где старая цель уже закончилась.

    Без этого закончившаяся цель запирала экран: человек видел итог и «можно взять новую»,
    но предложений не видел, пока не уберёт старую вручную — а убрать её не о чем: она уже
    отработала. Поэтому итог и новое предложение живут на одном экране.
    """
    if not candidates:
        return f"Новой пока нет. {GOAL_NOTHING}"
    return "\n".join(["**Можно взять следующую:**", *_candidate_lines(candidates)])
