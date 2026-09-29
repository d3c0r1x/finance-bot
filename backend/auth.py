"""Independent mobile identities and revocable, rotating sessions."""
import hashlib
import secrets
import sqlite3
import time
import uuid
from pathlib import Path

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError
from fastapi import HTTPException

PASSWORDS = PasswordHasher()
DUMMY_HASH = PASSWORDS.hash(secrets.token_urlsafe(32))


class AuthStore:
    def __init__(self, root: Path, secret: str):
        self.root, self.secret = root, secret
        root.mkdir(parents=True, exist_ok=True)
        self.path = root / "auth.sqlite3"
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
                    password TEXT NOT NULL, display_name TEXT NOT NULL,
                    language TEXT NOT NULL DEFAULT 'ru');
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
                    refresh_hash TEXT UNIQUE NOT NULL, expires INTEGER NOT NULL,
                    revoked INTEGER NOT NULL DEFAULT 0);
            """)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        return db

    def register(self, username, password, display_name, language):
        user_id = uuid.uuid4().hex
        password_hash = PASSWORDS.hash(password)
        try:
            with self.connect() as db:
                db.execute("INSERT INTO accounts VALUES (?,?,?,?,?)",
                           (user_id, username.lower(), password_hash, display_name, language))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "username_taken")
        return user_id

    def login(self, username, password):
        with self.connect() as db:
            row = db.execute("SELECT * FROM accounts WHERE username=?", (username.lower(),)).fetchone()
        try:
            PASSWORDS.verify(row["password"] if row else DUMMY_HASH, password)
        except VerificationError:
            raise HTTPException(401, "invalid_credentials")
        if not row:
            raise HTTPException(401, "invalid_credentials")
        return self.issue(row["id"])

    def issue(self, user_id):
        sid, refresh = uuid.uuid4().hex, secrets.token_urlsafe(48)
        with self.connect() as db:
            db.execute("INSERT INTO sessions VALUES (?,?,?,?,0)",
                       (sid, user_id, self.digest(refresh), int(time.time()) + 30 * 86400))
        return self.tokens(user_id, sid, refresh)

    @staticmethod
    def digest(value):
        return hashlib.sha256(value.encode()).hexdigest()

    def tokens(self, uid, sid, refresh):
        now = int(time.time())
        access = jwt.encode({"sub": uid, "sid": sid, "iat": now, "exp": now + 900,
                             "iss": "finance-mobile", "aud": "finance-app"},
                            self.secret, algorithm="HS256")
        return {"access_token": access, "refresh_token": refresh,
                "token_type": "bearer", "expires_in": 900}

    def rotate(self, refresh):
        replacement = secrets.token_urlsafe(48)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM sessions WHERE refresh_hash=? AND revoked=0 AND expires>?",
                             (self.digest(refresh), int(time.time()))).fetchone()
            if not row:
                raise HTTPException(401, "invalid_refresh_token")
            db.execute("UPDATE sessions SET refresh_hash=? WHERE id=?", (self.digest(replacement), row["id"]))
        return self.tokens(row["user_id"], row["id"], replacement)

    def authenticate(self, token):
        try:
            claims = jwt.decode(token, self.secret, algorithms=["HS256"],
                                issuer="finance-mobile", audience="finance-app",
                                options={"require": ["exp", "iat", "sub", "sid"]})
        except jwt.InvalidTokenError:
            raise HTTPException(401, "invalid_access_token")
        with self.connect() as db:
            row = db.execute("SELECT a.*,s.id AS session_id FROM accounts a JOIN sessions s ON a.id=s.user_id "
                             "WHERE a.id=? AND s.id=? AND s.revoked=0 AND s.expires>?",
                             (claims["sub"], claims["sid"], int(time.time()))).fetchone()
        if not row:
            raise HTTPException(401, "session_revoked")
        return dict(row)

    def logout(self, sid):
        with self.connect() as db:
            db.execute("UPDATE sessions SET revoked=1 WHERE id=?", (sid,))
