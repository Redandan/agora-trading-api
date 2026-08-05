from __future__ import annotations

import hashlib
import hmac
import html
import json
import os
import secrets
import sqlite3
import time
from pathlib import Path
from urllib.parse import urlparse

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    OAuthAuthorizationServerProvider,
    RefreshToken,
    RegistrationError,
    TokenError,
    construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken
from pydantic import AnyHttpUrl
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse, Response


SCOPES = ["research:read", "research:heartbeat", "offline_access"]
SUBJECT = "autonomous-research-sponsor"


class ResearchRefreshToken(RefreshToken):
    resource: str
    family_id: str
    subject: str


class SqliteOAuthProvider(
    OAuthAuthorizationServerProvider[
        AuthorizationCode,
        ResearchRefreshToken,
        AccessToken,
    ]
):
    """Single-sponsor OAuth provider with DCR, PKCE and rotating refresh tokens.

    The only human credential is a 256-bit one-time enrollment code. The server
    stores a PBKDF2 hash, consumes it after the first successful token exchange,
    and thereafter relies on the connector's rotating refresh token.
    """

    def __init__(
        self,
        *,
        database: Path,
        enrollment_hash: Path,
        enrollment_consumed: Path,
        issuer: str,
        resource: str,
        login_url: str,
        allow_local_redirects: bool = False,
    ) -> None:
        self.database = database
        self.enrollment_hash = enrollment_hash
        self.enrollment_consumed = enrollment_consumed
        self.issuer = issuer.rstrip("/")
        self.resource = resource
        self.login_url = login_url
        self.allow_local_redirects = allow_local_redirects
        self.database.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA busy_timeout=10000")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS oauth_clients (
                    client_id TEXT PRIMARY KEY,
                    client_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS authorization_flows (
                    flow_id TEXT PRIMARY KEY,
                    client_id TEXT NOT NULL,
                    client_state TEXT,
                    redirect_uri TEXT NOT NULL,
                    redirect_explicit INTEGER NOT NULL,
                    code_challenge TEXT NOT NULL,
                    resource TEXT NOT NULL,
                    scopes_json TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS authorization_codes (
                    code_hash TEXT PRIMARY KEY,
                    client_id TEXT NOT NULL,
                    redirect_uri TEXT NOT NULL,
                    redirect_explicit INTEGER NOT NULL,
                    code_challenge TEXT NOT NULL,
                    resource TEXT NOT NULL,
                    scopes_json TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    consumed_at INTEGER
                );
                CREATE TABLE IF NOT EXISTS access_tokens (
                    token_hash TEXT PRIMARY KEY,
                    client_id TEXT NOT NULL,
                    scopes_json TEXT NOT NULL,
                    resource TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    family_id TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    revoked_at INTEGER
                );
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                    token_hash TEXT PRIMARY KEY,
                    client_id TEXT NOT NULL,
                    scopes_json TEXT NOT NULL,
                    resource TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    family_id TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    revoked_at INTEGER
                );
                CREATE INDEX IF NOT EXISTS access_token_family_idx
                    ON access_tokens(family_id);
                CREATE INDEX IF NOT EXISTS refresh_token_family_idx
                    ON refresh_tokens(family_id);
                """
            )
        os.chmod(self.database, 0o600)

    @staticmethod
    def _digest(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    @staticmethod
    def _scopes(value: str) -> list[str]:
        parsed = json.loads(value)
        if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
            raise ValueError("invalid stored OAuth scopes")
        return parsed

    def _redirect_allowed(self, value: str) -> bool:
        parsed = urlparse(value)
        if parsed.scheme == "https" and parsed.hostname == "chatgpt.com":
            return True
        return bool(
            self.allow_local_redirects
            and parsed.scheme == "http"
            and parsed.hostname in {"localhost", "127.0.0.1"}
        )

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT client_json FROM oauth_clients WHERE client_id = ?",
                (client_id,),
            ).fetchone()
        if row is None:
            return None
        return OAuthClientInformationFull.model_validate_json(row["client_json"])

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        if not client_info.client_id:
            raise RegistrationError("invalid_client_metadata", "client_id is required")
        redirect_uris = client_info.redirect_uris or []
        if not redirect_uris or any(not self._redirect_allowed(str(uri)) for uri in redirect_uris):
            raise RegistrationError(
                "invalid_redirect_uri",
                "only ChatGPT connector callback URLs are accepted",
            )
        with self._connect() as connection:
            connection.execute(
                "INSERT OR REPLACE INTO oauth_clients(client_id, client_json, created_at) VALUES (?, ?, ?)",
                (client_info.client_id, client_info.model_dump_json(), int(time.time())),
            )

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        if not client.client_id:
            raise HTTPException(400, "invalid OAuth client")
        resource = params.resource or self.resource
        if resource != self.resource:
            raise HTTPException(400, "invalid OAuth resource")
        scopes = params.scopes or SCOPES
        if any(scope not in SCOPES for scope in scopes):
            raise HTTPException(400, "invalid OAuth scope")
        if "research:read" not in scopes or "research:heartbeat" not in scopes:
            raise HTTPException(400, "required research scopes are missing")
        flow_id = secrets.token_urlsafe(32)
        with self._connect() as connection:
            connection.execute("DELETE FROM authorization_flows WHERE expires_at < ?", (int(time.time()),))
            connection.execute(
                """
                INSERT INTO authorization_flows(
                    flow_id, client_id, client_state, redirect_uri,
                    redirect_explicit, code_challenge, resource, scopes_json, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    flow_id,
                    client.client_id,
                    params.state,
                    str(params.redirect_uri),
                    1 if params.redirect_uri_provided_explicitly else 0,
                    params.code_challenge,
                    resource,
                    json.dumps(scopes),
                    int(time.time()) + 600,
                ),
            )
        return f"{self.login_url}?flow={flow_id}"

    async def login_page(self, request: Request) -> HTMLResponse:
        flow_id = request.query_params.get("flow", "")
        if not flow_id:
            raise HTTPException(400, "missing OAuth flow")
        with self._connect() as connection:
            row = connection.execute(
                "SELECT expires_at FROM authorization_flows WHERE flow_id = ?",
                (flow_id,),
            ).fetchone()
        if row is None or int(row["expires_at"]) < int(time.time()):
            raise HTTPException(400, "OAuth flow expired")
        if self.enrollment_consumed.exists() or not self.enrollment_hash.exists():
            raise HTTPException(403, "this private connector is already enrolled")
        safe_flow = html.escape(flow_id, quote=True)
        return HTMLResponse(
            content=f"""<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Research Worker 連接</title>
<style>body{{font-family:system-ui,sans-serif;max-width:34rem;margin:4rem auto;padding:0 1.25rem;color:#172033}}label{{display:block;margin:1.5rem 0 .4rem}}input{{box-sizing:border-box;width:100%;padding:.8rem;border:1px solid #9aa4b2;border-radius:.5rem}}button{{margin-top:1.2rem;padding:.8rem 1.1rem;border:0;border-radius:.5rem;background:#126b4f;color:white;font-weight:650}}.note{{color:#566170;line-height:1.55}}</style></head>
<body><h1>連接私人 Research Worker</h1>
<p class="note">這是一次性連接。授權範圍只包含讀取研究狀態，以及提交受限的 heartbeat 請求；不包含交易、資料庫、資金或部署權限。</p>
<form method="post" action="/research/login"><input type="hidden" name="flow" value="{safe_flow}">
<label for="code">一次性連接碼</label><input id="code" name="code" type="password" autocomplete="one-time-code" required autofocus>
<button type="submit">完成連接</button></form></body></html>""",
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
        )

    def _enrollment_valid(self, supplied: str) -> bool:
        try:
            algorithm, iterations_raw, salt_hex, digest_hex = self.enrollment_hash.read_text(
                encoding="utf-8"
            ).strip().split("$")
            if algorithm != "pbkdf2_sha256":
                return False
            expected = bytes.fromhex(digest_hex)
            actual = hashlib.pbkdf2_hmac(
                "sha256",
                supplied.encode("utf-8"),
                bytes.fromhex(salt_hex),
                int(iterations_raw),
            )
            return hmac.compare_digest(actual, expected)
        except (OSError, ValueError):
            return False

    async def login_callback(self, request: Request) -> Response:
        form = await request.form()
        flow_id = form.get("flow")
        supplied = form.get("code")
        if not isinstance(flow_id, str) or not isinstance(supplied, str):
            raise HTTPException(400, "invalid enrollment request")
        if not self._enrollment_valid(supplied):
            raise HTTPException(401, "invalid or expired enrollment code")
        now = int(time.time())
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM authorization_flows WHERE flow_id = ?",
                (flow_id,),
            ).fetchone()
            if row is None or int(row["expires_at"]) < now:
                raise HTTPException(400, "OAuth flow expired")
            code = "research_code_" + secrets.token_urlsafe(32)
            connection.execute(
                """
                INSERT INTO authorization_codes(
                    code_hash, client_id, redirect_uri, redirect_explicit,
                    code_challenge, resource, scopes_json, subject, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    self._digest(code),
                    row["client_id"],
                    row["redirect_uri"],
                    row["redirect_explicit"],
                    row["code_challenge"],
                    row["resource"],
                    row["scopes_json"],
                    SUBJECT,
                    now + 300,
                ),
            )
            connection.execute("DELETE FROM authorization_flows WHERE flow_id = ?", (flow_id,))
        redirect = construct_redirect_uri(
            str(row["redirect_uri"]),
            code=code,
            state=row["client_state"],
        )
        return RedirectResponse(redirect, status_code=302)

    async def load_authorization_code(
        self,
        client: OAuthClientInformationFull,
        authorization_code: str,
    ) -> AuthorizationCode | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM authorization_codes WHERE code_hash = ? AND consumed_at IS NULL",
                (self._digest(authorization_code),),
            ).fetchone()
        if row is None or row["client_id"] != client.client_id:
            return None
        return AuthorizationCode(
            code=authorization_code,
            client_id=row["client_id"],
            redirect_uri=AnyHttpUrl(row["redirect_uri"]),
            redirect_uri_provided_explicitly=bool(row["redirect_explicit"]),
            expires_at=float(row["expires_at"]),
            scopes=self._scopes(row["scopes_json"]),
            code_challenge=row["code_challenge"],
            resource=row["resource"],
        )

    def _issue_tokens(
        self,
        connection: sqlite3.Connection,
        *,
        client_id: str,
        scopes: list[str],
        resource: str,
        subject: str,
        family_id: str,
    ) -> OAuthToken:
        now = int(time.time())
        access = "research_at_" + secrets.token_urlsafe(48)
        refresh = "research_rt_" + secrets.token_urlsafe(64)
        access_expires = now + 900
        refresh_expires = now + 31_536_000
        scopes_json = json.dumps(scopes)
        connection.execute(
            "INSERT INTO access_tokens VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
            (
                self._digest(access), client_id, scopes_json, resource,
                subject, family_id, access_expires,
            ),
        )
        connection.execute(
            "INSERT INTO refresh_tokens VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
            (
                self._digest(refresh), client_id, scopes_json, resource,
                subject, family_id, refresh_expires,
            ),
        )
        return OAuthToken(
            access_token=access,
            token_type="Bearer",
            expires_in=900,
            refresh_token=refresh,
            scope=" ".join(scopes),
        )

    async def exchange_authorization_code(
        self,
        client: OAuthClientInformationFull,
        authorization_code: AuthorizationCode,
    ) -> OAuthToken:
        if not client.client_id or authorization_code.resource != self.resource:
            raise TokenError("invalid_grant", "invalid client or resource")
        now = int(time.time())
        with self._connect() as connection:
            row = connection.execute(
                "SELECT consumed_at, expires_at, subject FROM authorization_codes WHERE code_hash = ?",
                (self._digest(authorization_code.code),),
            ).fetchone()
            if row is None or row["consumed_at"] is not None or int(row["expires_at"]) < now:
                raise TokenError("invalid_grant", "authorization code is invalid")
            connection.execute(
                "UPDATE authorization_codes SET consumed_at = ? WHERE code_hash = ?",
                (now, self._digest(authorization_code.code)),
            )
            token = self._issue_tokens(
                connection,
                client_id=client.client_id,
                scopes=authorization_code.scopes,
                resource=self.resource,
                subject=row["subject"],
                family_id=secrets.token_urlsafe(24),
            )
        self.enrollment_consumed.write_text(f"consumed_at={now}\n", encoding="utf-8")
        os.chmod(self.enrollment_consumed, 0o600)
        self.enrollment_hash.unlink(missing_ok=True)
        return token

    async def load_access_token(self, token: str) -> AccessToken | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM access_tokens WHERE token_hash = ? AND revoked_at IS NULL",
                (self._digest(token),),
            ).fetchone()
        if row is None or int(row["expires_at"]) < int(time.time()) or row["resource"] != self.resource:
            return None
        return AccessToken(
            token=token,
            client_id=row["client_id"],
            scopes=self._scopes(row["scopes_json"]),
            resource=row["resource"],
            expires_at=int(row["expires_at"]),
        )

    async def load_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: str,
    ) -> ResearchRefreshToken | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NULL",
                (self._digest(refresh_token),),
            ).fetchone()
        if (
            row is None
            or row["client_id"] != client.client_id
            or int(row["expires_at"]) < int(time.time())
            or row["resource"] != self.resource
        ):
            return None
        return ResearchRefreshToken(
            token=refresh_token,
            client_id=row["client_id"],
            scopes=self._scopes(row["scopes_json"]),
            resource=row["resource"],
            subject=row["subject"],
            expires_at=int(row["expires_at"]),
            family_id=row["family_id"],
        )

    async def exchange_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: ResearchRefreshToken,
        scopes: list[str],
    ) -> OAuthToken:
        if not client.client_id or refresh_token.resource != self.resource:
            raise TokenError("invalid_grant", "invalid refresh token resource")
        now = int(time.time())
        with self._connect() as connection:
            row = connection.execute(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                (self._digest(refresh_token.token),),
            ).fetchone()
            if row is None or row["revoked_at"] is not None:
                raise TokenError("invalid_grant", "refresh token was already used")
            connection.execute(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ?",
                (now, self._digest(refresh_token.token)),
            )
            return self._issue_tokens(
                connection,
                client_id=client.client_id,
                scopes=scopes,
                resource=self.resource,
                subject=refresh_token.subject or SUBJECT,
                family_id=refresh_token.family_id,
            )

    async def revoke_token(self, token: AccessToken | ResearchRefreshToken) -> None:
        digest = self._digest(token.token)
        now = int(time.time())
        with self._connect() as connection:
            row = connection.execute(
                "SELECT family_id FROM access_tokens WHERE token_hash = ? UNION ALL "
                "SELECT family_id FROM refresh_tokens WHERE token_hash = ? LIMIT 1",
                (digest, digest),
            ).fetchone()
            if row is None:
                return
            connection.execute(
                "UPDATE access_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                (now, row["family_id"]),
            )
            connection.execute(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                (now, row["family_id"]),
            )
